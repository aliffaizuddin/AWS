# shellcheck shell=bash
# 05 — Kill S3 mid-multipart. Uploaded parts must survive and the upload must be
# resumable; a complete interrupted by a kill must be retryable with the same result.

MIB=$((1024 * 1024))

scenario_run() {
  local id=05 token uid e1 e2 e3 e4 res sum got listed parts_xml body want
  record_metric "$id" title "Kill S3 mid-multipart"
  token=$(iam_token)

  # shellcheck disable=SC2016 # expanded by sh inside the pod
  sum=$(client_sh '
    for n in 1 2 3; do head -c "$1" /dev/urandom > /tmp/mp$n; done
    head -c "$2" /dev/urandom > /tmp/mp4
    cat /tmp/mp1 /tmp/mp2 /tmp/mp3 /tmp/mp4 | sha256sum | cut -d" " -f1' $((5 * MIB)) $((1 * MIB)))

  record_metric "$id" window_start_ms "$(now_ms)"
  uid=$(mp_create s05-a "$token")
  e1=$(mp_put_part s05-a "$uid" 1 /tmp/mp1 "$token"); e1=${e1#* }
  e2=$(mp_put_part s05-a "$uid" 2 /tmp/mp2 "$token"); e2=${e2#* }

  mp_put_part s05-a "$uid" 3 /tmp/mp3 "$token" --limit-rate 1M > "$RUN_DIR/05-part3.out" 2>/dev/null &
  local put_pid=$!
  sleep 1
  kill_pod s3
  wait "$put_pid" || true
  record_result "$id" interrupted-part INFO "part 3 during the kill: $(cat "$RUN_DIR/05-part3.out")"

  if ! wait_healthy 300; then
    record_result "$id" stack FAIL "stack not healthy within 300s of the kill"
    return 0
  fi

  listed=$(mp_list_uploads "$token")
  if grep -q " $uid\$" <<<"$listed"; then
    record_result "$id" rediscover-upload PASS "ListMultipartUploads still lists the upload after the restart"
  else
    record_result "$id" rediscover-upload FAIL "upload $uid missing from ListMultipartUploads: ${listed:-empty}"
  fi

  parts_xml=$(mp_list_parts s05-a "$uid" "$token")
  if [[ $parts_xml == *"<PartNumber>1</PartNumber><ETag>\"$e1\"</ETag>"* && $parts_xml == *"<PartNumber>2</PartNumber><ETag>\"$e2\"</ETag>"* ]]; then
    record_result "$id" parts-survived PASS "parts 1-2 listed with their original ETags"
  else
    record_result "$id" parts-survived FAIL "parts 1-2 not intact after restart"
  fi

  e3=$(mp_put_part s05-a "$uid" 3 /tmp/mp3 "$token"); e3=${e3#* }
  e4=$(mp_put_part s05-a "$uid" 4 /tmp/mp4 "$token"); e4=${e4#* }
  want=$(mp_etag "$e1" "$e2" "$e3" "$e4")
  res=$(mp_complete s05-a "$uid" "$(complete_body "1:$e1" "2:$e2" "3:$e3" "4:$e4")" "$token")
  got=$(s3_get_sha256 s05-a "$token")
  if [[ $res == "200 $want" && $got == "200 $sum" ]]; then
    record_result "$id" resumed-complete PASS "resumed upload completed with ETag $want; object sha256 matches"
  else
    record_result "$id" resumed-complete FAIL "complete returned '$res' (want 200 $want); GET returned '${got%% *}'"
  fi

  # Second upload: kill S3 right after firing complete. Either outcome is valid;
  # a retry must then succeed with the same ETag.
  uid=$(mp_create s05-b "$token")
  e1=$(mp_put_part s05-b "$uid" 1 /tmp/mp1 "$token"); e1=${e1#* }
  e2=$(mp_put_part s05-b "$uid" 2 /tmp/mp4 "$token"); e2=${e2#* }
  # shellcheck disable=SC2016 # expanded by sh inside the pod
  sum=$(client_sh 'cat /tmp/mp1 /tmp/mp4 | sha256sum | cut -d" " -f1')
  want=$(mp_etag "$e1" "$e2")
  body=$(complete_body "1:$e1" "2:$e2")
  mp_complete s05-b "$uid" "$body" "$token" > "$RUN_DIR/05-complete1.out" 2>/dev/null &
  local complete_pid=$!
  kill_pod s3
  wait "$complete_pid" || true
  record_result "$id" first-complete INFO "complete in flight during the kill returned: $(cat "$RUN_DIR/05-complete1.out") (either a result or a dropped connection is valid)"

  if ! wait_healthy 300; then
    record_result "$id" stack FAIL "stack not healthy within 300s of the second kill"
    return 0
  fi
  record_metric "$id" window_end_ms "$(now_ms)"

  res=$(mp_complete s05-b "$uid" "$body" "$token")
  got=$(s3_get_sha256 s05-b "$token")
  if [[ $res == "200 $want" && $got == "200 $sum" ]]; then
    record_result "$id" retried-complete PASS "retried complete returned 200 with ETag $want; object sha256 matches"
  else
    record_result "$id" retried-complete FAIL "retry returned '$res' (want 200 $want); GET returned '${got%% *}'"
  fi

  client_sh 'rm -f /tmp/mp1 /tmp/mp2 /tmp/mp3 /tmp/mp4'
}
