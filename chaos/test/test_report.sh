# shellcheck shell=bash
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/probe.sh
source "$CHAOS_DIR/lib/probe.sh"
# shellcheck source=../lib/report.sh
source "$CHAOS_DIR/lib/report.sh"

_fresh_run_dir() { RUN_DIR=$(mktemp -d); init_run_dir; }

test_fmt_secs() {
  assert_eq 41.3s "$(fmt_secs 41300)" "fmt_secs: ms to seconds"
  assert_eq 0.0s "$(fmt_secs 0)" "fmt_secs: zero"
  assert_eq - "$(fmt_secs -)" "fmt_secs: dash passthrough"
}

test_fmt_utc() {
  assert_eq "2026-01-01 00:00:00" "$(fmt_utc 1767225600000)" "fmt_utc: epoch ms to UTC"
}

test_scenario_status_worst_wins() {
  _fresh_run_dir
  record_result 01 a PASS x
  record_result 01 b WARN y
  record_result 01 c INFO z
  record_result 02 a PASS x
  record_result 02 b FAIL y
  record_result 04 c INFO only
  assert_eq WARN "$(scenario_status 01)" "status: WARN beats PASS, INFO ignored"
  assert_eq FAIL "$(scenario_status 02)" "status: FAIL beats PASS"
  assert_eq "NOT RUN" "$(scenario_status 03)" "status: nothing recorded"
  assert_eq "NOT RUN" "$(scenario_status 04)" "status: INFO-only is not a run"
}

test_overall_exit_code() {
  _fresh_run_dir
  record_result 01 a PASS x
  record_result 01 b WARN y
  assert_eq 0 "$(overall_exit_code 0)" "exit: PASS+WARN is 0"
  record_result 02 a FAIL y
  assert_eq 1 "$(overall_exit_code 0)" "exit: any FAIL is 1"
  assert_eq 2 "$(overall_exit_code 1)" "exit: aborted is 2"
}

test_check_recovery() {
  _fresh_run_dir
  check_recovery 01 $'samples=20\noutage_seen=yes\nrecovered=yes\nrecovery_ms=41300' 120 s3
  check_recovery 02 $'samples=20\noutage_seen=yes\nrecovered=yes\nrecovery_ms=130000' 120 s3
  check_recovery 03 $'samples=20\noutage_seen=yes\nrecovered=no\nrecovery_ms=-' 120 iam
  check_recovery 04 $'samples=20\noutage_seen=no\nrecovered=-\nrecovery_ms=-' 120 s3
  assert_eq PASS "$(scenario_status 01)" "recovery: within limit passes"
  assert_eq 41300 "$(metric 01 recovery_ms_s3)" "recovery: metric recorded"
  assert_eq FAIL "$(scenario_status 02)" "recovery: over limit fails"
  assert_eq FAIL "$(scenario_status 03)" "recovery: never recovered fails"
  assert_eq WARN "$(scenario_status 04)" "recovery: no outage observed warns"
}

test_render_report() {
  local meta out
  _fresh_run_dir
  meta="$RUN_DIR/meta"
  printf '%s\n' run_id=20260101-000000 context=k3d-test git_sha=abc1234 \
    'started_utc=2026-01-01 00:00:00 UTC' image_s3=s3:0.1.0 image_iam=iam:0.1.0 \
    image_postgres=postgres:16-alpine aborted=no > "$meta"
  record_metric 01 title "Kill IAM"
  record_metric 01 window_start_ms 1767225600000
  record_metric 01 window_end_ms 1767225660000
  record_metric 01 recovery_ms_s3 41300
  record_result 01 fail-closed PASS "no 2xx during outage"
  out=$(render_report "$meta" 01 02)
  assert_contains "$out" "# Chaos run 20260101-000000" "report: title"
  assert_contains "$out" "| Context | \`k3d-test\` |" "report: context"
  assert_contains "$out" "| 01 Kill IAM | PASS | s3 41.3s | 2026-01-01 00:00:00 → 2026-01-01 00:01:00 | 1767225600000–1767225660000 |" "report: summary row"
  assert_contains "$out" "| 02 | NOT RUN | - | - | - |" "report: not-run row"
  assert_contains "$out" "## 01 Kill IAM" "report: scenario section"
  assert_contains "$out" "| fail-closed | PASS | no 2xx during outage |" "report: check row"
}

test_render_report_escapes_pipes_and_newlines() {
  local meta out
  _fresh_run_dir
  meta="$RUN_DIR/meta"
  printf '%s\n' run_id=r context=c git_sha=s started_utc=t image_s3=a image_iam=b image_postgres=c aborted=yes > "$meta"
  record_result 01 weird FAIL $'a | b\nsecond line'
  out=$(render_report "$meta" 01)
  assert_contains "$out" '| weird | FAIL | a \| b second line |' "report: pipe escaped, newline flattened"
  assert_contains "$out" "**Run aborted**" "report: aborted banner"
}

test_check_recovery_fails_when_probe_never_ran() {
  _fresh_run_dir
  check_recovery 05 $'samples=0\noutage_seen=no\nrecovered=-\nrecovery_ms=-' 120 s3
  assert_eq FAIL "$(scenario_status 05)" "recovery: a probe with no samples is a FAIL, not a WARN"
}

_parsed() {  # _parsed <samples> <outage_seen> <recovered> <recovery_ms> <flap> [down_until_ms]
  printf 'samples=%s\noutage_seen=%s\nrecovered=%s\nrecovery_ms=%s\nflap_2xx=%s\noutage_statuses=500\ndown_until_ms=%s\n' \
    "$1" "$2" "$3" "$4" "$5" "${6:--}"
}

test_check_fail_closed_passes_when_s3_recovers_after_iam_was_last_seen_down() {
  _fresh_run_dir
  check_fail_closed 01 "$(_parsed 40 yes yes 13000 0)" "$(_parsed 40 yes yes 13500 0 12000)"
  assert_eq PASS "$(scenario_status 01)" "fail-closed: S3 back after IAM's last observed refusal passes"
}

test_check_fail_closed_fails_when_s3_serves_while_iam_refuses() {
  _fresh_run_dir
  check_fail_closed 01 "$(_parsed 40 yes yes 3000 0)" "$(_parsed 40 yes yes 12500 0 12000)"
  assert_eq FAIL "$(scenario_status 01)" "fail-closed: stable 2xx while IAM still refusing is a fail-open"
}

test_check_fail_closed_tolerates_probe_skew() {
  _fresh_run_dir
  check_fail_closed 01 "$(_parsed 40 yes yes 11700 0)" "$(_parsed 40 yes yes 12500 0 12000)"
  assert_eq PASS "$(scenario_status 01)" "fail-closed: under 500ms of probe skew is tolerated"
}

test_check_fail_closed_ignores_iam_probe_blind_spots() {
  # Live run 20261006-074606: IAM's probe hung 2s on a dead endpoint, so its
  # first 2xx (13.0s) lagged S3's (11.6s); IAM was last seen refusing at 10.6s.
  _fresh_run_dir
  check_fail_closed 01 "$(_parsed 60 yes yes 11600 0)" "$(_parsed 60 yes yes 13000 0 10600)"
  assert_eq PASS "$(scenario_status 01)" "fail-closed: a timed-out IAM sample is not evidence IAM was down"
}

test_check_fail_closed_fails_on_flapping_or_no_outage() {
  _fresh_run_dir
  check_fail_closed 01 "$(_parsed 40 yes yes 13000 2)" "$(_parsed 40 yes yes 12000 0 11000)"
  check_fail_closed 02 "$(_parsed 40 no - - 0)" "$(_parsed 40 yes yes 12000 0 11000)"
  assert_eq FAIL "$(scenario_status 01)" "fail-closed: 2xx mid-outage fails"
  assert_eq FAIL "$(scenario_status 02)" "fail-closed: no S3 outage at all fails"
}

test_check_fail_closed_fails_when_s3_serves_during_an_iam_timeout_phase() {
  # IAM refused until 10s, then only timed out until it came back at 20s; an S3
  # that fails open on IAM timeouts would be back at 11s.
  _fresh_run_dir
  check_fail_closed 01 "$(_parsed 80 yes yes 11000 0)" "$(_parsed 80 yes yes 20000 0 10000)"
  assert_eq FAIL "$(scenario_status 01)" "fail-closed: 2xx during IAM's timeout phase is a fail-open"
}

test_check_fail_closed_uses_iam_recovery_when_only_timeouts_seen() {
  _fresh_run_dir
  check_fail_closed 01 "$(_parsed 40 yes yes 3000 0)" "$(_parsed 40 yes yes 12000 0 -)"
  check_fail_closed 02 "$(_parsed 40 yes yes 11000 0)" "$(_parsed 40 yes yes 12000 0 -)"
  assert_eq FAIL "$(scenario_status 01)" "fail-closed: judged against IAM recovery minus probe timeout"
  assert_eq PASS "$(scenario_status 02)" "fail-closed: within IAM's last probe window passes"
}

test_check_fail_closed_unjudgeable_or_unrun() {
  _fresh_run_dir
  check_fail_closed 01 "$(_parsed 0 no - - 0)" "$(_parsed 40 yes yes 12000 0 11000)"
  check_fail_closed 02 "$(_parsed 40 yes yes 13000 0)" "$(_parsed 40 yes no - 0 -)"
  assert_eq FAIL "$(scenario_status 01)" "fail-closed: S3 probe never ran"
  assert_eq WARN "$(scenario_status 02)" "fail-closed: IAM neither seen refusing nor recovered can't be judged"
}
