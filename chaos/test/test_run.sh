# shellcheck shell=bash
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../run.sh
source "$CHAOS_DIR/run.sh"

test_resolve_context() {
  local out rc
  out=$(resolve_context k3d-cloudlite-test "" 2>/dev/null) && rc=0 || rc=$?
  assert_eq "0 k3d-cloudlite-test" "$rc $out" "context: k3d- prefix accepted"
  out=$(resolve_context prod-cluster "" 2>/dev/null) && rc=0 || rc=$?
  assert_eq 2 "$rc" "context: non-k3d refused"
  out=$(resolve_context "" "" 2>/dev/null) && rc=0 || rc=$?
  assert_eq 2 "$rc" "context: no current context refused"
  out=$(resolve_context prod-cluster my-node 2>/dev/null) && rc=0 || rc=$?
  assert_eq "0 my-node" "$rc $out" "context: explicit override wins"
}

test_parse_args() {
  local rc
  parse_args --setup-only --context foo
  assert_eq "1 foo" "$SETUP_ONLY $CONTEXT_OVERRIDE" "args: flags parsed"
  parse_args 99 2>/dev/null && rc=0 || rc=$?
  assert_eq 2 "$rc" "args: unknown scenario rejected"
  parse_args --bogus 2>/dev/null && rc=0 || rc=$?
  assert_eq 2 "$rc" "args: unknown option rejected"
  parse_args --context 2>/dev/null && rc=0 || rc=$?
  assert_eq 2 "$rc" "args: --context needs a value"
  parse_args
  assert_eq "$(scenario_ids | paste -sd' ')" "${SELECTED[*]}" "args: default selects all scenarios"
}

test_report_path_includes_seconds() {
  local CHAOS_CONTEXT=k3d-test RUN_ID=20260101-000007
  assert_eq "$CHAOS_DIR/reports/2026-01-01-000007-k3d-test.md" "$(report_path)" "report path: seconds included"
}

test_run_help_from_other_cwd() {
  local out
  out=$(cd /tmp && bash "$CHAOS_DIR/run.sh" --help)
  assert_contains "$out" "Usage:" "help works from any cwd"
}

test_parse_args_selects_named_scenario() {
  parse_args 01
  assert_eq "01" "${SELECTED[*]}" "args: explicit id selects only that scenario"
}
