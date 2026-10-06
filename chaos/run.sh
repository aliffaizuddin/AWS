#!/usr/bin/env bash
# CloudLite chaos suite. See docs/platform/chaos.md.
# shellcheck source-path=SCRIPTDIR
set -euo pipefail

CHAOS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$CHAOS_DIR/lib/probe.sh"
source "$CHAOS_DIR/lib/report.sh"
source "$CHAOS_DIR/lib/s3xml.sh"
source "$CHAOS_DIR/lib/cluster.sh"

usage() {
  cat <<'USAGE'
Usage: chaos/run.sh [--context NAME] [--setup-only] [SCENARIO_ID...]

Runs CloudLite chaos scenarios against a k3d cluster and writes a report
to chaos/reports/. With no IDs, runs every scenario in chaos/scenarios/.

  --context NAME   use this kube context (bypasses the k3d-* guard)
  --setup-only     preflight + setup + teardown, no scenarios
  -h, --help       show this help

Exit: 0 all pass (warnings allowed), 1 a scenario failed, 2 aborted.
USAGE
}

scenario_ids() {
  local f
  for f in "$CHAOS_DIR"/scenarios/[0-9][0-9]-*.sh; do
    [[ -e $f ]] || continue
    f=${f##*/}
    echo "${f%%-*}"
  done
}

scenario_file() {
  local f
  for f in "$CHAOS_DIR"/scenarios/"$1"-*.sh; do
    echo "$f"
    return 0
  done
}

# resolve_context <current> <override>
resolve_context() {
  if [[ -n $2 ]]; then
    echo "$2"
  elif [[ $1 == k3d-* ]]; then
    echo "$1"
  else
    echo "refusing to run against context '${1:-<none>}': not a k3d-* context (pass --context NAME to override)" >&2
    return 2
  fi
}

parse_args() {
  local all
  CONTEXT_OVERRIDE=""
  SETUP_ONLY=0
  SELECTED=()
  all=$(scenario_ids)
  while (($#)); do
    case $1 in
      --context)
        [[ $# -ge 2 ]] || { echo "--context needs a value" >&2; return 2; }
        CONTEXT_OVERRIDE=$2
        shift 2
        ;;
      --setup-only) SETUP_ONLY=1; shift ;;
      -h | --help) usage; exit 0 ;;
      -*) echo "unknown option: $1" >&2; return 2 ;;
      *)
        grep -qx -- "$1" <<<"$all" || { echo "unknown scenario: $1" >&2; return 2; }
        SELECTED+=("$1")
        shift
        ;;
    esac
  done
  if ((${#SELECTED[@]} == 0)) && [[ -n $all ]]; then
    mapfile -t SELECTED <<<"$all"
  fi
}

report_path() {
  local d=${RUN_ID%%-*} t=${RUN_ID#*-}
  echo "$CHAOS_DIR/reports/${d:0:4}-${d:4:2}-${d:6:2}-$t-${CHAOS_CONTEXT//[^A-Za-z0-9._-]/-}.md"
}

# kill_tree <pid> — SIGTERM a process and all its descendants. Background
# jobs of a non-interactive shell ignore SIGINT, so Ctrl-C alone would
# leave a scenario's probe loops and kubectl execs running.
kill_tree() {
  local pids=("$1") i=0 c
  kill -STOP "$1" 2>/dev/null || return 0
  while ((i < ${#pids[@]})); do
    for c in $(pgrep -P "${pids[i]}"); do
      pids+=("$c")
    done
    i=$((i + 1))
  done
  kill -TERM "${pids[@]}" 2>/dev/null
  kill -CONT "$1" 2>/dev/null
  return 0
}

SCENARIO_PID=""

# on_signal <exit_code> — stop the running scenario, then exit (teardown runs on EXIT).
on_signal() {
  [[ -n $SCENARIO_PID ]] && kill_tree "$SCENARIO_PID"
  exit "$1"
}

write_meta() {
  local a
  {
    echo "run_id=$RUN_ID"
    echo "context=$CHAOS_CONTEXT"
    echo "git_sha=$(git -C "$CHAOS_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo "started_utc=$(date -u '+%Y-%m-%d %H:%M:%S UTC')"
    for a in s3 iam postgres; do
      echo "image_$a=$(kc get pods -l "app=$a" -o jsonpath='{.items[0].spec.containers[0].image}')"
    done
  } > "$RUN_DIR/meta"
}

main() {
  local aborted=0 id rc report
  parse_args "$@" || exit 2
  require_tools
  CHAOS_CONTEXT=$(resolve_context "$(kubectl config current-context 2>/dev/null || true)" "$CONTEXT_OVERRIDE") || exit 2
  RUN_ID=$(date -u +%Y%m%d-%H%M%S)
  RUN_DIR="$CHAOS_DIR/reports/raw/$RUN_ID"
  export CHAOS_CONTEXT RUN_ID RUN_DIR
  init_run_dir
  log "run $RUN_ID against context $CHAOS_CONTEXT"

  trap teardown EXIT
  trap 'on_signal 130' INT
  trap 'on_signal 143' TERM
  set -E
  trap 'log "ERROR: setup failed (line $LINENO)"; exit 2' ERR
  start_client_pod
  stack_healthy || die "preflight: s3/iam/postgres must be Ready and /healthz must return 200"
  setup_identity
  write_meta
  trap - ERR
  set +E

  if ((SETUP_ONLY)); then
    log "setup ok (--setup-only); tearing down"
    exit 0
  fi

  for id in "${SELECTED[@]}"; do
    log "scenario $id"
    # Run in the background and wait, so a trapped INT/TERM interrupts the
    # wait at once instead of after the scenario finishes.
    set +e
    (
      set -e
      # shellcheck source=/dev/null
      source "$(scenario_file "$id")"
      scenario_run
    ) &
    SCENARIO_PID=$!
    wait "$SCENARIO_PID"
    rc=$?
    SCENARIO_PID=""
    set -e
    if ((rc != 0)); then
      record_result "$id" scenario-script FAIL "scenario script exited $rc; see the run log"
    fi
    if ! wait_healthy 300; then
      record_result "$id" stack-recovered FAIL "stack not healthy 300s after the scenario; remaining scenarios skipped"
      aborted=1
      break
    fi
  done

  echo "aborted=$( ((aborted)) && echo yes || echo no)" >> "$RUN_DIR/meta"
  report=$(report_path)
  render_report "$RUN_DIR/meta" "${SELECTED[@]}" > "$report"
  log "report: $report"
  exit "$(overall_exit_code "$aborted")"
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
  main "$@"
fi
