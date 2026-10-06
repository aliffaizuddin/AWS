# shellcheck shell=bash
# 01 — Kill IAM. S3 must fail closed while IAM is gone, then recover.

scenario_run() {
  local id=01 token seed raw probe_pid kill_ms parsed flap
  record_metric "$id" title "Kill IAM"
  token=$(iam_token)
  seed=$(s3_put_random s01-probe 1024 "$token")
  if [[ ${seed%% *} != 200 ]]; then
    record_result "$id" seed FAIL "seed PUT returned ${seed%% *}"
    return 0
  fi

  raw="$RUN_DIR/01-s3-get.log"
  record_metric "$id" window_start_ms "$(now_ms)"
  probe_until_recovered "$raw" 180 "$S3_URL/$CHAOS_BUCKET/s01-probe" -H "Authorization: Bearer $token" &
  probe_pid=$!
  sleep 2
  kill_ms=$(now_ms)
  kill_pod iam
  wait "$probe_pid" || true
  record_metric "$id" window_end_ms "$(now_ms)"

  parsed=$(parse_probe "$raw" "$kill_ms")
  flap=$(kv flap_2xx <<<"$parsed")
  if [[ $(kv outage_seen <<<"$parsed") == no ]]; then
    record_result "$id" fail-closed FAIL "S3 kept answering 2xx after IAM was killed (possible fail-open)"
  elif ((flap > 0)); then
    record_result "$id" fail-closed FAIL "$flap 2xx response(s) between the first error and stable recovery"
  else
    record_result "$id" fail-closed PASS "no 2xx between the first error and stable recovery"
  fi
  check_recovery "$id" "$parsed" 120 s3
  record_result "$id" outage-status INFO "S3 returned $(kv outage_statuses <<<"$parsed") while IAM was down (503 would be the accurate status for an unavailable dependency)"
}
