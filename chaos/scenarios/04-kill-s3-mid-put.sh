# shellcheck shell=bash
# 04 — Kill S3 during a large PUT. The object must be all-or-nothing afterwards.

scenario_run() {
  local id=04 token url sum put_pid put_code put_rc got res audit size=$((80 * 1024 * 1024))
  record_metric "$id" title "Kill S3 mid-PUT"
  token=$(iam_token)
  url="$S3_URL/$CHAOS_BUCKET/s04-big"
  echo s04-big >> "$RUN_DIR/keys"
  # shellcheck disable=SC2016 # expanded by sh inside the pod
  sum=$(client_sh 'head -c "$1" /dev/urandom > /tmp/chaos-big && sha256sum /tmp/chaos-big | cut -d" " -f1' "$size")

  record_metric "$id" window_start_ms "$(now_ms)"
  # shellcheck disable=SC2016 # expanded by sh inside the pod
  client_sh 'c=$(curl -s -o /dev/null -w "%{http_code}" --limit-rate 4M -T /tmp/chaos-big -H "Authorization: Bearer $2" "$1"); echo "$c $?"' \
    "$url" "$token" > "$RUN_DIR/04-put.out" 2>/dev/null &
  put_pid=$!
  sleep 2
  kill_pod s3
  wait "$put_pid" || true
  read -r put_code put_rc < "$RUN_DIR/04-put.out" || true
  record_result "$id" interrupted-put INFO "client's last HTTP status ${put_code:-none} (100 = Continue, no final response), curl exit ${put_rc:-none} (52/56 = connection dropped mid-transfer)"

  if ! wait_healthy 300; then
    record_result "$id" stack FAIL "stack not healthy within 300s of the kill"
    return 0
  fi
  record_metric "$id" window_end_ms "$(now_ms)"

  got=$(s3_get_sha256 s04-big "$token")
  case $got in
    "404 -") record_result "$id" all-or-nothing PASS "object absent after the interrupted PUT" ;;
    "200 $sum") record_result "$id" all-or-nothing PASS "object complete and matches the uploaded sha256" ;;
    *) record_result "$id" all-or-nothing FAIL "partial or corrupt object visible: HTTP ${got%% *}, sha256 ${got#* }" ;;
  esac

  # shellcheck disable=SC2016 # expanded by sh inside the pod
  res=$(client_sh 'curl -s -o /dev/null -w "%{http_code}" --max-time 120 -T /tmp/chaos-big -H "Authorization: Bearer $2" "$1"' "$url" "$token")
  got=$(s3_get_sha256 s04-big "$token")
  if [[ $res == 200 && $got == "200 $sum" ]]; then
    record_result "$id" retry-put PASS "retried PUT succeeded and reads back identical"
  else
    record_result "$id" retry-put FAIL "retried PUT returned $res; read back ${got}"
  fi

  audit=$(blob_audit)
  if [[ $audit == "tmp=0 orphans=0" ]]; then
    record_result "$id" blob-audit PASS "no leftover .tmp files or orphaned blobs in /data"
  else
    record_result "$id" blob-audit WARN "$audit across /data (storage leak, not a visibility bug)"
  fi
  record_result "$id" coverage INFO "S3 buffers the whole body before writing to disk, so a kill during transfer can only leave the object absent; the window between blob rename and metadata save is not exercised"

  client_sh 'rm -f /tmp/chaos-big'
}
