# shellcheck shell=bash
# 03 — Kill Postgres. S3 and IAM (shared database) must both recover with no data loss.

scenario_run() {
  local id=03 token seed first raw_s3 raw_iam p_s3 p_iam kill_ms fresh
  record_metric "$id" title "Kill Postgres"
  token=$(iam_token)
  seed="$RUN_DIR/03-seed.txt"
  if ! seed_objects s03 10 65536 "$token" "$seed"; then
    record_result "$id" seed FAIL "could not seed 10 objects"
    return 0
  fi
  first=$(head -n1 "$seed" | cut -d' ' -f1)

  raw_s3="$RUN_DIR/03-s3-get.log"
  raw_iam="$RUN_DIR/03-iam-token.log"
  record_metric "$id" window_start_ms "$(now_ms)"
  probe_until_recovered "$raw_s3" 240 "$S3_URL/$CHAOS_BUCKET/$first" -H "Authorization: Bearer $token" &
  p_s3=$!
  probe_until_recovered "$raw_iam" 240 "$IAM_URL/auth/token" -X POST -H "Authorization: ApiKey $CHAOS_API_KEY" &
  p_iam=$!
  sleep 2
  kill_ms=$(now_ms)
  kill_pod postgres
  wait "$p_s3" || true
  wait "$p_iam" || true
  record_metric "$id" window_end_ms "$(now_ms)"

  check_recovery "$id" "$(parse_probe "$raw_s3" "$kill_ms")" 180 s3
  check_recovery "$id" "$(parse_probe "$raw_iam" "$kill_ms")" 180 iam

  if fresh=$(iam_token 2>/dev/null) && [[ -n $fresh && $fresh != null ]]; then
    record_result "$id" iam-data PASS "test user's API key still mints a token after the restart"
    check_objects "$id" objects-intact "$seed" "$fresh"
  else
    record_result "$id" iam-data FAIL "test user's API key no longer mints a token"
    check_objects "$id" objects-intact "$seed" "$token"
  fi
}
