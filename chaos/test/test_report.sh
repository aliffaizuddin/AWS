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
  check_recovery 01 $'outage_seen=yes\nrecovered=yes\nrecovery_ms=41300' 120 s3
  check_recovery 02 $'outage_seen=yes\nrecovered=yes\nrecovery_ms=130000' 120 s3
  check_recovery 03 $'outage_seen=yes\nrecovered=no\nrecovery_ms=-' 120 iam
  check_recovery 04 $'outage_seen=no\nrecovered=yes\nrecovery_ms=0' 120 s3
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
