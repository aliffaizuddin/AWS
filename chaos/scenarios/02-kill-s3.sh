# shellcheck shell=bash
# 02 — Kill S3. S3 must come back, and objects written before the kill must survive.

scenario_run() {
  local id=02 token seed first raw probe_pid kill_ms
  record_metric "$id" title "Kill S3"
  token=$(iam_token)
  seed="$RUN_DIR/02-seed.txt"
  if ! seed_objects s02 10 65536 "$token" "$seed"; then
    record_result "$id" seed FAIL "could not seed 10 objects"
    return 0
  fi
  first=$(head -n1 "$seed" | cut -d' ' -f1)

  raw="$RUN_DIR/02-s3-get.log"
  record_metric "$id" window_start_ms "$(now_ms)"
  probe_until_recovered "$raw" 180 "$S3_URL/$CHAOS_BUCKET/$first" -H "Authorization: Bearer $token" &
  probe_pid=$!
  sleep 2
  kill_ms=$(now_ms)
  kill_pod s3
  wait "$probe_pid" || true
  record_metric "$id" window_end_ms "$(now_ms)"

  check_recovery "$id" "$(parse_probe "$raw" "$kill_ms")" 120 s3
  check_objects "$id" objects-intact "$seed" "$token"
}
