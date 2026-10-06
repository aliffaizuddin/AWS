# shellcheck shell=bash
# 01 — Kill IAM. S3 must fail closed while IAM is gone, then recover.

scenario_run() {
  local id=01 token seed raw_s3 raw_iam p_s3 p_iam kill_ms s3_parsed iam_parsed
  record_metric "$id" title "Kill IAM"
  token=$(iam_token)
  seed=$(s3_put_random s01-probe 1024 "$token")
  if [[ ${seed%% *} != 200 ]]; then
    record_result "$id" seed FAIL "seed PUT returned ${seed%% *}"
    return 0
  fi

  # IAM's own /healthz is probed alongside S3, so fail-closed can check
  # that S3 only answers 2xx again once IAM is actually back.
  raw_s3="$RUN_DIR/01-s3-get.log"
  raw_iam="$RUN_DIR/01-iam-healthz.log"
  record_metric "$id" window_start_ms "$(now_ms)"
  probe_until_recovered "$raw_s3" 180 "$S3_URL/$CHAOS_BUCKET/s01-probe" -H "Authorization: Bearer $token" &
  p_s3=$!
  probe_until_recovered "$raw_iam" 180 "$IAM_URL/healthz" &
  p_iam=$!
  sleep 2
  kill_ms=$(now_ms)
  kill_pod iam
  wait "$p_s3" || true
  wait "$p_iam" || true
  record_metric "$id" window_end_ms "$(now_ms)"

  s3_parsed=$(parse_probe "$raw_s3" "$kill_ms")
  iam_parsed=$(parse_probe "$raw_iam" "$kill_ms")
  check_fail_closed "$id" "$s3_parsed" "$iam_parsed"
  check_recovery "$id" "$s3_parsed" 120 s3
  check_recovery "$id" "$iam_parsed" 120 iam
  record_result "$id" outage-status INFO "S3 returned $(kv outage_statuses <<<"$s3_parsed") while IAM was down (expected 503 ServiceUnavailable; 000 = request timed out client-side)"
}
