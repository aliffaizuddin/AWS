# shellcheck shell=bash
# Everything that touches the cluster. Requires CHAOS_CONTEXT and RUN_DIR.

CHAOS_NS=cloudlite
CLIENT_POD=chaos-client
CLIENT_IMAGE=curlimages/curl:8.10.1
S3_URL=http://s3:8080
IAM_URL=http://iam:8081

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 2; }
now_ms() { date +%s%3N; }

kc() { kubectl --context "$CHAOS_CONTEXT" -n "$CHAOS_NS" "$@"; }

# client_sh <script> [args...] — POSIX sh in the client pod; args become $1...
client_sh() {
  local script=$1
  shift
  kc exec "$CLIENT_POD" -- sh -c "$script" sh "$@" </dev/null
}

# client_curl [curl args...] — curl from inside the cluster; non-zero on HTTP >= 400.
client_curl() { kc exec "$CLIENT_POD" -- curl -sS --fail-with-body --max-time 10 "$@" </dev/null; }

require_tools() {
  local t
  for t in kubectl jq git; do
    command -v "$t" >/dev/null || die "missing required tool: $t"
  done
}

start_client_pod() {
  kc apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: $CLIENT_POD
  labels:
    app: chaos-client
spec:
  restartPolicy: Never
  terminationGracePeriodSeconds: 1
  containers:
    - name: client
      image: $CLIENT_IMAGE
      command: ["sleep", "86400"]
      resources:
        requests: {cpu: 10m, memory: 32Mi}
        limits: {cpu: 500m, memory: 256Mi}
YAML
  kc wait --for=condition=Ready "pod/$CLIENT_POD" --timeout=120s >/dev/null
}

# pods_ready <app> — exactly one pod with that label, Ready, not terminating.
pods_ready() {
  local out
  out=$(kc get pods -l "app=$1" \
    -o jsonpath='{range .items[*]}{.metadata.deletionTimestamp}|{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}') || return 1
  [[ $out == "|True" ]]
}

stack_healthy() {
  local a
  for a in s3 iam postgres; do
    pods_ready "$a" || return 1
  done
  client_curl -o /dev/null "$S3_URL/healthz" 2>/dev/null &&
    client_curl -o /dev/null "$IAM_URL/healthz" 2>/dev/null
}

# wait_healthy <timeout_s>
wait_healthy() {
  local deadline=$((SECONDS + $1))
  until stack_healthy; do
    ((SECONDS < deadline)) || return 1
    sleep 2
  done
}

iam_token() {
  client_curl -X POST "$IAM_URL/auth/token" -H "Authorization: ApiKey $CHAOS_API_KEY" | jq -r .token
}

setup_identity() {
  local user policy_doc policy
  CHAOS_BUCKET="chaos-$RUN_ID"
  user=$(client_curl -X POST "$IAM_URL/users" -H 'Content-Type: application/json' \
    -d "{\"username\":\"chaos-$RUN_ID\"}")
  CHAOS_USER_ID=$(jq -r .id <<<"$user")
  CHAOS_API_KEY=$(jq -r .apiKey <<<"$user")
  policy_doc=$(jq -nc --arg n "chaos-$RUN_ID" --arg b "$CHAOS_BUCKET" '{
    name: $n,
    document: {statements: [{
      effect: "ALLOW",
      actions: ["s3:CreateBucket", "s3:DeleteBucket", "s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      resources: ["arn:cloudlite:s3:::\($b)", "arn:cloudlite:s3:::\($b)/*"]
    }]}
  }')
  policy=$(client_curl -X POST "$IAM_URL/policies" -H 'Content-Type: application/json' -d "$policy_doc")
  client_curl -X POST "$IAM_URL/users/$CHAOS_USER_ID/policies/$(jq -r .id <<<"$policy")" >/dev/null
  export CHAOS_USER_ID CHAOS_API_KEY CHAOS_BUCKET
  client_curl -X PUT -o /dev/null "$S3_URL/$CHAOS_BUCKET" -H "Authorization: Bearer $(iam_token)"
}

# s3_put_random <key> <bytes> <token> — prints "<status> <sha256>".
s3_put_random() {
  echo "$1" >> "$RUN_DIR/keys"
  # shellcheck disable=SC2016 # expanded by sh inside the pod
  client_sh '
    f=$(mktemp)
    head -c "$2" /dev/urandom > "$f"
    s=$(sha256sum "$f" | cut -d" " -f1)
    c=$(curl -s -o /dev/null -w "%{http_code}" --max-time 60 -T "$f" -H "Authorization: Bearer $3" "$1")
    rm -f "$f"
    echo "$c $s"' "$S3_URL/$CHAOS_BUCKET/$1" "$2" "$3"
}

# s3_get_sha256 <key> <token> — prints "<status> <sha256|->".
s3_get_sha256() {
  # shellcheck disable=SC2016 # expanded by sh inside the pod
  client_sh '
    f=$(mktemp)
    c=$(curl -s -o "$f" -w "%{http_code}" --max-time 60 -H "Authorization: Bearer $2" "$1")
    if [ "$c" = 200 ]; then s=$(sha256sum "$f" | cut -d" " -f1); else s=-; fi
    rm -f "$f"
    echo "$c $s"' "$S3_URL/$CHAOS_BUCKET/$1" "$2"
}

# In-pod probe loop. Args: timeout_s, stable_run, url, then extra curl args.
# Prints one HTTP status per request (timestamped on the host by
# stamp_lines); exits once a failure has been seen followed by stable_run
# consecutive 2xx, or at the timeout.
# shellcheck disable=SC2016 # expanded by sh inside the pod
PROBE_SCRIPT='
end=$(( $(date +%s) + $1 )); need=$2; url=$3; shift 3
seen_fail=0; ok=0
while [ "$(date +%s)" -lt "$end" ]; do
  c=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "$@" "$url")
  echo "$c"
  case $c in
    2??) ok=$((ok + 1)) ;;
    *) seen_fail=1; ok=0 ;;
  esac
  if [ "$seen_fail" = 1 ] && [ "$ok" -ge "$need" ]; then exit 0; fi
  sleep 0.25
done'

# probe_until_recovered <out_file> <timeout_s> <url> [curl args...]
probe_until_recovered() {
  local out=$1 timeout=$2 url=$3
  shift 3
  client_sh "$PROBE_SCRIPT" "$timeout" "$CHAOS_STABLE_RUN" "$url" "$@" | stamp_lines > "$out"
}

kill_pod() { kc delete pod -l "app=$1" --wait=false >/dev/null; }

teardown() {
  set +e
  local jobs_left tok key
  jobs_left=$(jobs -p)
  # shellcheck disable=SC2086 # one PID per word
  [[ -n $jobs_left ]] && kill $jobs_left 2>/dev/null
  if [[ -n ${CHAOS_API_KEY:-} ]] && kc get pod "$CLIENT_POD" >/dev/null 2>&1; then
    tok=$(iam_token 2>/dev/null)
    if [[ -n $tok && $tok != null ]]; then
      while read -r key; do
        client_curl -X DELETE -o /dev/null -H "Authorization: Bearer $tok" "$S3_URL/$CHAOS_BUCKET/$key" 2>/dev/null
      done < <(sort -u "$RUN_DIR/keys" 2>/dev/null)
      client_curl -X DELETE -o /dev/null -H "Authorization: Bearer $tok" "$S3_URL/$CHAOS_BUCKET" 2>/dev/null ||
        log "warn: could not delete bucket $CHAOS_BUCKET"
    else
      log "warn: no IAM token at teardown; bucket $CHAOS_BUCKET left in place"
    fi
  fi
  kc delete pod "$CLIENT_POD" --wait=false --ignore-not-found >/dev/null 2>&1
  if [[ -n ${CHAOS_API_KEY:-} ]]; then
    log "IAM user/policy chaos-$RUN_ID left in place (IAM has no DELETE endpoints)"
  fi
}
