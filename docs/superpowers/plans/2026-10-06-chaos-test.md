# Chaos Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A repeatable bash chaos suite (`chaos/run.sh`) that kills IAM, S3, and Postgres pods on a local k3d CloudLite stack, measures recovery and correctness, and writes a Markdown report.

**Architecture:** Host-side bash orchestrates `kubectl`; all HTTP traffic and timed probe loops run inside a throwaway in-cluster `chaos-client` pod (curl image) against Service DNS names, so measurements survive pod restarts. Pure logic (probe-log parsing, results/report rendering, arg/context handling) lives in sourced libs with offline unit tests; cluster-touching code is verified by live runs against k3d.

**Tech Stack:** bash 5, kubectl, jq, curl (`curlimages/curl:8.10.1`, busybox userland), awk, ShellCheck, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-06-chaos-test-design.md`

## Global Constraints

- Namespace `cloudlite`; pod labels `app=s3`, `app=iam`, `app=postgres`; Deployments `s3`, `iam`; StatefulSet `postgres`.
- Service URLs from inside the cluster: `http://s3:8080`, `http://iam:8081`.
- Context guard: refuse unless current context starts with `k3d-`, or `--context <name>` is passed (then that context is used).
- Probe interval 250ms; per-request `curl --max-time 2`; recovery = start of first run of **5** consecutive 2xx after the outage begins.
- Recovery limits: scenarios 01/02 **120s**, scenario 03 **180s** (each of S3 and IAM). Probe hard timeout = limit + 60s.
- Wait-for-healthy between scenarios: timeout **300s**; timeout ⇒ abort remaining scenarios.
- Seed objects: **10 × 64 KiB** (scenarios 02, 03). Mid-PUT object: **80 MiB**, `curl --limit-rate 4M`, kill **~2s** after start.
- Exit codes: `0` all PASS (WARN allowed) · `1` any FAIL · `2` preflight / context guard / setup / infrastructure abort.
- Result statuses: `PASS | FAIL | WARN | INFO` (INFO never affects a scenario's status).
- Test identity: user + policy named `chaos-<RUN_ID>`, bucket `chaos-<RUN_ID>`, `RUN_ID` = `date -u +%Y%m%d-%H%M%S`. Policy actions exactly: `s3:CreateBucket`, `s3:DeleteBucket`, `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`.
- IAM token: `POST /auth/token` with header `Authorization: ApiKey <key>` → `{"token": ...}`. S3 calls use `Authorization: Bearer <token>`.
- Raw run data under `chaos/reports/raw/<RUN_ID>/` (gitignored). Reports at `chaos/reports/<YYYY-MM-DD-HHMMSS>-<context>.md`.
- Host is Linux (GNU `date`); no macOS support.
- Commits: Conventional Commits; branch `feat/chaos-test` off `main`.

### Deviations from the spec (deliberate, decided while planning)

- **`lib.sh` is split into `chaos/lib/{probe,report,cluster}.sh`** so pure logic can be unit-tested offline without stubbing kubectl.
- **IAM user/policy are not deleted on teardown** — IAM exposes no DELETE endpoints for users or policies (only POST/GET). They're uniquely named per run and the leftover is logged. Bucket and objects *are* deleted.
- **Report filename uses `HHMMSS`, not `HHMM`** — two runs in the same minute would otherwise overwrite each other's report.
- **`--setup-only` flag added** — runs context guard → preflight → setup → teardown with no scenarios. Used to verify the harness before any pod is killed.

### Spec open items — resolved

- Orphan check: table `objects`, column `storage_id` (UUID) — `services/s3/src/main/resources/db/migration/V2__create_objects.sql`. Blob files in `/data` are named `<uuid>`; temp files `<uuid>.<uuid>.tmp` (`DiskBlobStore.put`).
- S3 memory: `-Xmx768m`, limit 1Gi. `readBoundedBody` uses a `ByteArrayOutputStream`, peaking around 2.5× body size (~210 MiB for 80 MiB) — fits. Keep 80 MiB.
- `curlimages/curl` is Alpine-based with busybox (`sh`, `head`, `sha256sum`, `mktemp`, `date`). `date +%s%N` and fractional `sleep` are verified live in Task 4 Step 9 before anything depends on them.
- S3 runtime image `eclipse-temurin:21-jre` (Ubuntu) has `sh` and `ls`. Postgres `postgres:16-alpine` allows passwordless local-socket `psql` by default — verified in Task 8 Step 2.

## Review Focus

1. **Report detail containing `|` or a newline** (e.g. a curl error message) — must not break the Markdown tables; pinned by `test_render_report_escapes_pipes_and_newlines` (Task 3).
2. **Empty or garbage probe log** (client exec failed instantly, or kubectl wrote a warning to the log) — must parse as "no outage seen", never as a clean recovery, and garbage lines must be ignored; pinned by `test_parse_probe_empty_log` and `test_parse_probe_ignores_garbage_lines` (Task 2).
3. **Running from another working directory** (`/tmp$ ~/…/chaos/run.sh --help`) — paths resolve from the script's own location; pinned by `test_run_help_from_other_cwd` (Task 4).
4. **Two runs started in the same minute** — must not overwrite each other's report; pinned by `test_report_path_includes_seconds` (Task 4).
5. **Ctrl-C mid-scenario** — teardown must still delete the client pod and stop background probes; pinned by a manual interrupt check in Task 5 Step 5 (it needs a live cluster).

---

## File Structure

```
chaos/
  run.sh                      # entry point: args, context guard, preflight, setup, scenario loop, report, exit code
  lib/
    probe.sh                  # PURE: parse_probe, kv
    report.sh                 # PURE: init_run_dir, record_result/metric, metric, scenario_status,
                              #       overall_exit_code, check_recovery, fmt_*, md_escape, render_report
    cluster.sh                # kubectl/HTTP: kc, client_sh, client_curl, client pod, health, identity,
                              #       S3 object helpers, probe loop, kill_pod, blob_audit, teardown
  scenarios/
    01-kill-iam.sh            # each defines scenario_run()
    02-kill-s3.sh
    03-kill-postgres.sh
    04-kill-s3-mid-put.sh
  test/
    run-tests.sh              # tiny assert framework + test discovery
    test_probe.sh
    test_report.sh
    test_run.sh
  reports/
    .gitkeep
.github/workflows/ci-chaos.yml
docs/platform/chaos.md
docs/future-work.md           # modified
.gitignore                    # modified
```

---

### Task 1: Scaffold, local tooling, test runner, CI

**Files:**
- Create: `chaos/test/run-tests.sh`, `chaos/reports/.gitkeep`, `.github/workflows/ci-chaos.yml`
- Modify: `.gitignore`

**Interfaces:**
- Produces: `chaos/test/run-tests.sh` — sources every `chaos/test/test_*.sh`, runs every function named `test_*`, prints `passed: N, failed: M`, exits non-zero if any failed. Assertion helpers available to test files: `assert_eq <expected> <actual> <message>`, `assert_contains <haystack> <needle> <message>`. Variables `CHAOS_DIR` (absolute path to `chaos/`) and `TEST_DIR` are set before test files are sourced.

- [ ] **Step 1: Create the branch**

```bash
git checkout main && git pull --ff-only
git checkout -b feat/chaos-test
```

- [ ] **Step 2: Install `jq` and `shellcheck` locally if missing**

```bash
mkdir -p ~/.local/bin
command -v jq || { curl -fsSL -o ~/.local/bin/jq https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-amd64 && chmod +x ~/.local/bin/jq; }
command -v shellcheck || { curl -fsSL https://github.com/koalaman/shellcheck/releases/download/v0.10.0/shellcheck-v0.10.0.linux.x86_64.tar.xz | tar -xJ -C /tmp && mv /tmp/shellcheck-v0.10.0/shellcheck ~/.local/bin/; }
jq --version && shellcheck --version | head -2
```
Expected: `jq-1.7.1` (or newer) and `version: 0.10.0` (or newer).

- [ ] **Step 3: Write the test runner**

`chaos/test/run-tests.sh`:
```bash
#!/usr/bin/env bash
# Offline unit tests for the chaos suite's pure helpers. No cluster needed.
set -euo pipefail

TEST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CHAOS_DIR=$(cd "$TEST_DIR/.." && pwd)

PASSED=0
FAILED=0

# assert_eq <expected> <actual> <message>
assert_eq() {
  if [[ $1 == "$2" ]]; then
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
    printf 'FAIL: %s\n  expected: %q\n  actual:   %q\n' "$3" "$1" "$2" >&2
  fi
}

# assert_contains <haystack> <needle> <message>
assert_contains() {
  if [[ $1 == *"$2"* ]]; then
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
    printf 'FAIL: %s\n  missing: %s\n  in:\n%s\n' "$3" "$2" "$1" >&2
  fi
}

for f in "$TEST_DIR"/test_*.sh; do
  [[ -e $f ]] || continue
  # shellcheck source=/dev/null
  source "$f"
done

for t in $(declare -F | awk '{print $3}' | grep '^test_' || true); do
  "$t"
done

echo "passed: $PASSED, failed: $FAILED"
((FAILED == 0))
```

```bash
chmod +x chaos/test/run-tests.sh
mkdir -p chaos/reports && touch chaos/reports/.gitkeep
```

- [ ] **Step 4: Run it with no tests**

Run: `chaos/test/run-tests.sh`
Expected: `passed: 0, failed: 0`, exit 0.

- [ ] **Step 5: Gitignore raw run data**

Append to `.gitignore`:
```
chaos/reports/raw/
```

- [ ] **Step 6: Add the CI workflow**

`.github/workflows/ci-chaos.yml`:
```yaml
name: CI - Chaos suite

on:
  pull_request:
    paths:
      - "chaos/**"
      - ".github/workflows/ci-chaos.yml"
  push:
    branches: [main]
    paths:
      - "chaos/**"
      - ".github/workflows/ci-chaos.yml"

jobs:
  lint-and-test:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: ShellCheck
        run: find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x

      - name: Offline unit tests
        run: chaos/test/run-tests.sh
```
(`ubuntu-latest` ships `shellcheck` and `jq` preinstalled.)

- [ ] **Step 7: Lint and commit**

Run: `find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x`
Expected: no output, exit 0.

```bash
git add chaos/test/run-tests.sh chaos/reports/.gitkeep .gitignore .github/workflows/ci-chaos.yml
git commit -m "ci(chaos): scaffold chaos suite test runner and lint workflow"
```

---

### Task 2: Probe-log parser (`chaos/lib/probe.sh`)

**Files:**
- Create: `chaos/lib/probe.sh`
- Test: `chaos/test/test_probe.sh`

**Interfaces:**
- Consumes: `assert_eq`, `CHAOS_DIR` from Task 1.
- Produces:
  - `parse_probe <raw_file> <kill_ms>` → stdout `key=value` lines: `outage_seen=yes|no`, `outage_start_ms=<ms>|-`, `recovered=yes|no`, `recovery_ms=<int>|-`, `flap_2xx=<int>`, `outage_statuses=<s1,s2,…>|-`. No outage ⇒ `outage_seen=no`, `recovered=yes`, `recovery_ms=0`.
  - `kv <key>` — reads `parse_probe` output on stdin, prints that key's value.
  - `CHAOS_STABLE_RUN` (default 5).
  - Probe log format consumed: one line per request, `<epoch_ms> <http_status>`, status `000` = no HTTP response.

- [ ] **Step 1: Write the failing tests**

`chaos/test/test_probe.sh`:
```bash
# shellcheck shell=bash
# shellcheck source=../lib/probe.sh
source "$CHAOS_DIR/lib/probe.sh"

_probe_log() { local f; f=$(mktemp); cat > "$f"; echo "$f"; }

test_parse_probe_clean_outage_and_recovery() {
  local f out
  f=$(_probe_log <<'EOF'
900 200
1000 200
1250 500
1500 500
1750 200
2000 200
2250 200
2500 200
2750 200
EOF
)
  out=$(parse_probe "$f" 1000)
  assert_eq yes "$(kv outage_seen <<<"$out")" "clean: outage seen"
  assert_eq 1250 "$(kv outage_start_ms <<<"$out")" "clean: outage start"
  assert_eq yes "$(kv recovered <<<"$out")" "clean: recovered"
  assert_eq 750 "$(kv recovery_ms <<<"$out")" "clean: recovery measured from kill to start of stable run"
  assert_eq 0 "$(kv flap_2xx <<<"$out")" "clean: no flapping"
  assert_eq 500 "$(kv outage_statuses <<<"$out")" "clean: statuses"
}

test_parse_probe_flapping_counts_short_2xx_runs() {
  local f out
  f=$(_probe_log <<'EOF'
1250 000
1500 200
1750 500
2000 200
2250 200
2500 200
2750 200
3000 200
EOF
)
  out=$(parse_probe "$f" 1000)
  assert_eq 1 "$(kv flap_2xx <<<"$out")" "flap: one 2xx inside the outage"
  assert_eq 1000 "$(kv recovery_ms <<<"$out")" "flap: recovery is the stable run, not the blip"
  assert_eq 000,500 "$(kv outage_statuses <<<"$out")" "flap: statuses in first-seen order"
}

test_parse_probe_never_recovers() {
  local f out
  f=$(_probe_log <<'EOF'
1250 500
1500 200
1750 200
EOF
)
  out=$(parse_probe "$f" 1000)
  assert_eq no "$(kv recovered <<<"$out")" "never: not recovered"
  assert_eq - "$(kv recovery_ms <<<"$out")" "never: no recovery time"
  assert_eq 0 "$(kv flap_2xx <<<"$out")" "never: trailing short run is not flapping"
}

test_parse_probe_no_outage() {
  local f out
  f=$(_probe_log <<'EOF'
900 500
1000 200
1250 200
EOF
)
  out=$(parse_probe "$f" 1000)
  assert_eq no "$(kv outage_seen <<<"$out")" "no-outage: errors before the kill are ignored"
  assert_eq 0 "$(kv recovery_ms <<<"$out")" "no-outage: recovery 0"
}

test_parse_probe_empty_log() {
  local f out
  f=$(mktemp)
  out=$(parse_probe "$f" 1000)
  assert_eq no "$(kv outage_seen <<<"$out")" "empty: no outage seen"
}

test_parse_probe_ignores_garbage_lines() {
  local f out
  f=$(_probe_log <<'EOF'
Defaulted container "client" out of: client
1250 500

1500 200
1750 200
2000 200
2250 200
2500 200
EOF
)
  out=$(parse_probe "$f" 1000)
  assert_eq 1250 "$(kv outage_start_ms <<<"$out")" "garbage: ignored"
  assert_eq 500 "$(kv recovery_ms <<<"$out")" "garbage: recovery unaffected"
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `chaos/test/run-tests.sh`
Expected: FAIL — `source: .../chaos/lib/probe.sh: No such file or directory`.

- [ ] **Step 3: Implement**

`chaos/lib/probe.sh`:
```bash
# shellcheck shell=bash
# Probe-log parsing. Pure: no kubectl, no network — unit-tested offline.
#
# A probe log has one line per request: "<epoch_ms> <http_status>", where
# status 000 means curl got no HTTP response (refused or timed out).

# Consecutive 2xx responses required before a service counts as recovered.
CHAOS_STABLE_RUN=${CHAOS_STABLE_RUN:-5}

# parse_probe <raw_file> <kill_ms>
# Prints key=value lines:
#   outage_seen      yes|no — any non-2xx at or after kill_ms
#   outage_start_ms  first non-2xx at or after kill_ms, or -
#   recovered        yes|no — a run of CHAOS_STABLE_RUN 2xx after the outage began
#   recovery_ms      kill_ms to the first request of that run, or -
#   flap_2xx         2xx after the outage began that were not part of the stable run
#   outage_statuses  distinct non-2xx statuses after kill_ms, first-seen order, or -
parse_probe() {
  local raw=$1 kill_ms=$2
  awk -v kill="$kill_ms" -v need="$CHAOS_STABLE_RUN" '
    function is2xx(s) { return s ~ /^2[0-9][0-9]$/ }
    function note(s) { if (!(s in seen)) { seen[s] = 1; order = order (order == "" ? "" : ",") s } }
    NF != 2 || $1 !~ /^[0-9]+$/ || $1 + 0 < kill + 0 { next }
    {
      ts = $1; st = $2
      if (!outage) {
        if (!is2xx(st)) { outage = 1; outage_start = ts; note(st) }
        next
      }
      if (recovered) next
      if (is2xx(st)) {
        if (run == 0) run_start = ts
        run++
        if (run >= need) { recovered = 1; recovery_point = run_start }
      } else {
        flap += run; run = 0; note(st)
      }
    }
    END {
      if (!outage) {
        print "outage_seen=no"; print "outage_start_ms=-"; print "recovered=yes"
        print "recovery_ms=0"; print "flap_2xx=0"; print "outage_statuses=-"
        exit
      }
      print "outage_seen=yes"
      print "outage_start_ms=" outage_start
      print "recovered=" (recovered ? "yes" : "no")
      print "recovery_ms=" (recovered ? recovery_point - kill : "-")
      print "flap_2xx=" (flap + 0)
      print "outage_statuses=" order
    }' "$raw"
}

# kv <key> — read one value out of parse_probe output on stdin.
kv() { sed -n "s/^$1=//p"; }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `chaos/test/run-tests.sh`
Expected: `passed: 17, failed: 0`.

- [ ] **Step 5: Lint and commit**

```bash
find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x
git add chaos/lib/probe.sh chaos/test/test_probe.sh
git commit -m "feat(chaos): add probe-log parser for outage and recovery timing"
```

---

### Task 3: Results recording and report rendering (`chaos/lib/report.sh`)

**Files:**
- Create: `chaos/lib/report.sh`
- Test: `chaos/test/test_report.sh`

**Interfaces:**
- Consumes: `kv` from Task 2.
- Produces (all read/write files under `$RUN_DIR`):
  - `init_run_dir` — `mkdir -p $RUN_DIR`; truncates `results.tsv`, `metrics.tsv`, `keys`.
  - `record_result <scenario> <check> <PASS|FAIL|WARN|INFO> <detail>` → appends to `results.tsv` (tab-separated).
  - `record_metric <scenario> <key> <value>` → appends to `metrics.tsv`. Keys: `title`, `window_start_ms`, `window_end_ms`, `recovery_ms_<label>`.
  - `metric <scenario> <key>` → last value or empty.
  - `scenario_status <scenario>` → `PASS|WARN|FAIL|NOT RUN` (worst non-INFO).
  - `overall_exit_code <aborted:0|1>` → prints `0|1|2`.
  - `check_recovery <scenario> <parse_probe output> <limit_s> <label>` → records `recovery-<label>` result and `recovery_ms_<label>` metric.
  - `fmt_secs <ms|->` → `41.3s` or `-`; `fmt_utc <ms>` → `YYYY-MM-DD HH:MM:SS`.
  - `md_escape <text>` → `|` escaped, newlines to spaces.
  - `meta_get <meta_file> <key>`; meta file is `key=value` lines: `run_id`, `context`, `git_sha`, `started_utc`, `image_s3`, `image_iam`, `image_postgres`, `aborted` (`yes|no`).
  - `render_report <meta_file> <scenario ids…>` → Markdown on stdout.

- [ ] **Step 1: Write the failing tests**

`chaos/test/test_report.sh`:
```bash
# shellcheck shell=bash
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `chaos/test/run-tests.sh`
Expected: FAIL — `source: .../chaos/lib/report.sh: No such file or directory`.

- [ ] **Step 3: Implement**

`chaos/lib/report.sh`:
```bash
# shellcheck shell=bash
# Results/metrics recording and Markdown report rendering. Pure file I/O
# under $RUN_DIR — unit-tested offline. Requires kv (lib/probe.sh).
#
#   $RUN_DIR/results.tsv  scenario<TAB>check<TAB>PASS|FAIL|WARN|INFO<TAB>detail
#   $RUN_DIR/metrics.tsv  scenario<TAB>key<TAB>value
#   $RUN_DIR/keys         S3 object keys created this run (for teardown)

init_run_dir() {
  mkdir -p "$RUN_DIR"
  : > "$RUN_DIR/results.tsv"
  : > "$RUN_DIR/metrics.tsv"
  : > "$RUN_DIR/keys"
}

# Tabs and newlines in the detail are flattened so every result stays one TSV row.
record_result() {
  local detail=${4//$'\n'/ }
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "${detail//$'\t'/ }" >> "$RUN_DIR/results.tsv"
}
record_metric() { printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$RUN_DIR/metrics.tsv"; }

# metric <scenario> <key> — last recorded value, or empty.
metric() { awk -F'\t' -v s="$1" -v k="$2" '$1 == s && $2 == k { v = $3 } END { print v }' "$RUN_DIR/metrics.tsv"; }

# scenario_status <scenario> — worst non-INFO result, or NOT RUN.
scenario_status() {
  awk -F'\t' -v s="$1" '
    $1 == s && $3 != "INFO" { n++; if ($3 == "FAIL") f = 1; else if ($3 == "WARN") w = 1 }
    END { print (n == 0 ? "NOT RUN" : f ? "FAIL" : w ? "WARN" : "PASS") }' "$RUN_DIR/results.tsv"
}

# overall_exit_code <aborted:0|1>
overall_exit_code() {
  if (($1)); then
    echo 2
  elif awk -F'\t' '$3 == "FAIL" { f = 1 } END { exit !f }' "$RUN_DIR/results.tsv"; then
    echo 1
  else
    echo 0
  fi
}

# check_recovery <scenario> <parse_probe output> <limit_s> <label>
check_recovery() {
  local id=$1 parsed=$2 limit_s=$3 label=$4 rec
  if [[ $(kv outage_seen <<<"$parsed") == no ]]; then
    record_result "$id" "recovery-$label" WARN "no outage observed for $label after the kill"
    return 0
  fi
  if [[ $(kv recovered <<<"$parsed") != yes ]]; then
    record_result "$id" "recovery-$label" FAIL "$label did not recover within the probe timeout"
    return 0
  fi
  rec=$(kv recovery_ms <<<"$parsed")
  record_metric "$id" "recovery_ms_$label" "$rec"
  if ((rec <= limit_s * 1000)); then
    record_result "$id" "recovery-$label" PASS "$label recovered in $(fmt_secs "$rec") (limit ${limit_s}s)"
  else
    record_result "$id" "recovery-$label" FAIL "$label recovered in $(fmt_secs "$rec"), over the ${limit_s}s limit"
  fi
}

fmt_secs() { if [[ $1 == - ]]; then echo -; else awk -v ms="$1" 'BEGIN { printf "%.1fs\n", ms / 1000 }'; fi; }
fmt_utc() { date -u -d "@$(($1 / 1000))" '+%Y-%m-%d %H:%M:%S'; }

md_escape() {
  local s=${1//|/\\|}
  echo "${s//$'\n'/ }"
}

meta_get() { sed -n "s/^$2=//p" "$1" | tail -n1; }

# recovery_summary <scenario> — "s3 41.3s, iam 9.1s", or -.
recovery_summary() {
  local parts=() key val out
  while IFS=$'\t' read -r key val; do
    parts+=("${key#recovery_ms_} $(fmt_secs "$val")")
  done < <(awk -F'\t' -v s="$1" '$1 == s && $2 ~ /^recovery_ms_/ { print $2 "\t" $3 }' "$RUN_DIR/metrics.tsv")
  if ((${#parts[@]} == 0)); then
    echo -
  else
    out=$(printf '%s, ' "${parts[@]}")
    echo "${out%, }"
  fi
}

# render_report <meta_file> <scenario ids...>
render_report() {
  local meta=$1 id title start end window_utc window_ms check status detail
  shift
  echo "# Chaos run $(meta_get "$meta" run_id)"
  echo
  echo "| | |"
  echo "|---|---|"
  echo "| Context | \`$(meta_get "$meta" context)\` |"
  echo "| Git SHA | \`$(meta_get "$meta" git_sha)\` |"
  echo "| Started | $(meta_get "$meta" started_utc) |"
  echo "| Images | s3 \`$(meta_get "$meta" image_s3)\`, iam \`$(meta_get "$meta" image_iam)\`, postgres \`$(meta_get "$meta" image_postgres)\` |"
  echo
  if [[ $(meta_get "$meta" aborted) == yes ]]; then
    echo "**Run aborted** — the stack did not return to healthy after a scenario; later scenarios did not run."
    echo
  fi
  echo "## Summary"
  echo
  echo "| Scenario | Result | Recovery | Window (UTC) | Window (epoch ms) |"
  echo "|---|---|---|---|---|"
  for id in "$@"; do
    title=$(metric "$id" title)
    start=$(metric "$id" window_start_ms)
    end=$(metric "$id" window_end_ms)
    if [[ -n $start && -n $end ]]; then
      window_utc="$(fmt_utc "$start") → $(fmt_utc "$end")"
      window_ms="$start–$end"
    else
      window_utc=-
      window_ms=-
    fi
    echo "| $id${title:+ $title} | $(scenario_status "$id") | $(recovery_summary "$id") | $window_utc | $window_ms |"
  done
  for id in "$@"; do
    title=$(metric "$id" title)
    echo
    echo "## $id${title:+ $title}"
    echo
    echo "| Check | Result | Detail |"
    echo "|---|---|---|"
    while IFS=$'\t' read -r check status detail; do
      echo "| $check | $status | $(md_escape "$detail") |"
    done < <(awk -F'\t' -v s="$id" '$1 == s { print $2 "\t" $3 "\t" $4 }' "$RUN_DIR/results.tsv")
  done
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `chaos/test/run-tests.sh`
Expected: `failed: 0`.

- [ ] **Step 5: Lint and commit**

```bash
find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x
git add chaos/lib/report.sh chaos/test/test_report.sh
git commit -m "feat(chaos): add results recording and Markdown report rendering"
```

---

### Task 4: Cluster helpers and `run.sh` harness (no scenarios yet)

**Prerequisite:** a local k3d CloudLite stack, stood up per `LOCAL_TESTING_CHECKLIST.md` §0–§6 (cluster, Sealed Secrets, re-sealed secrets, ArgoCD, synced `cloudlite` app). Confirm: `kubectl get pods -n cloudlite` shows `s3`, `iam`, `postgres-0` Running/Ready.

**Files:**
- Create: `chaos/lib/cluster.sh`, `chaos/run.sh`
- Test: `chaos/test/test_run.sh`

**Interfaces:**
- Consumes: everything from Tasks 2–3.
- Produces (`cluster.sh`; all require `CHAOS_CONTEXT`, `RUN_DIR`):
  - constants `CHAOS_NS=cloudlite`, `CLIENT_POD=chaos-client`, `CLIENT_IMAGE=curlimages/curl:8.10.1`, `S3_URL=http://s3:8080`, `IAM_URL=http://iam:8081`
  - `log <msg…>`, `die <msg…>` (exit 2), `now_ms`
  - `kc <kubectl args…>` — kubectl pinned to `--context $CHAOS_CONTEXT -n cloudlite`
  - `client_sh <script> [args…]` — run POSIX sh in the client pod; args become `$1…`
  - `client_curl [curl args…]` — curl from the client pod; non-zero on HTTP ≥ 400
  - `require_tools`, `start_client_pod`, `pods_ready <app>`, `stack_healthy`, `wait_healthy <timeout_s>`
  - `iam_token` → JWT on stdout
  - `setup_identity` → exports `CHAOS_USER_ID`, `CHAOS_API_KEY`, `CHAOS_BUCKET`
  - `s3_put_random <key> <bytes> <token>` → `"<status> <sha256>"`; appends key to `$RUN_DIR/keys`
  - `s3_get_sha256 <key> <token>` → `"<status> <sha256|->"`
  - `probe_until_recovered <out_file> <timeout_s> <url> [curl args…]` — in-pod probe loop, writes probe log
  - `kill_pod <app>`
  - `teardown`
- Produces (`run.sh`, sourceable without running — `main` only runs when executed):
  - `scenario_ids` → `01`, `02`, … from `chaos/scenarios/NN-*.sh`
  - `scenario_file <id>` → path
  - `resolve_context <current> <override>` → chosen context on stdout, or return 2
  - `parse_args <args…>` → sets `CONTEXT_OVERRIDE`, `SETUP_ONLY` (0|1), `SELECTED` (array); returns 2 on bad input
  - `report_path` → `chaos/reports/<YYYY-MM-DD-HHMMSS>-<context>.md` (derived from `RUN_ID`)
  - Scenario contract: each `chaos/scenarios/NN-*.sh` defines `scenario_run` (no args), which records results/metrics for id `NN` and returns 0. Any non-zero exit is recorded as a `scenario-script` FAIL.

- [ ] **Step 1: Write the failing tests**

`chaos/test/test_run.sh`:
```bash
# shellcheck shell=bash
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
```

Note: with no scenario files yet, `scenario_ids` is empty and the default-selection assertion compares two empty strings — it gains teeth from Task 5 onward.

- [ ] **Step 2: Run tests to verify they fail**

Run: `chaos/test/run-tests.sh`
Expected: FAIL — `source: .../chaos/run.sh: No such file or directory`.

- [ ] **Step 3: Implement `chaos/lib/cluster.sh`**

```bash
# shellcheck shell=bash
# Everything that touches the cluster. Requires CHAOS_CONTEXT and RUN_DIR.

CHAOS_NS=cloudlite
CLIENT_POD=chaos-client
CLIENT_IMAGE=curlimages/curl:8.10.1
S3_URL=http://s3:8080
IAM_URL=http://iam:8081

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 2; }
now_ms() { date +%s%3N; }

kc() { kubectl --context "$CHAOS_CONTEXT" -n "$CHAOS_NS" "$@"; }

# client_sh <script> [args...] — POSIX sh in the client pod; args become $1...
client_sh() {
  local script=$1
  shift
  kc exec "$CLIENT_POD" -- sh -c "$script" sh "$@" </dev/null
}

# client_curl [curl args...] — curl from inside the cluster; non-zero on HTTP >= 400.
client_curl() { kc exec "$CLIENT_POD" -- curl -sS --fail-with-body --max-time 10 "$@" </dev/null; }

require_tools() {
  local t
  for t in kubectl jq git; do
    command -v "$t" >/dev/null || die "missing required tool: $t"
  done
}

start_client_pod() {
  kc apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $CLIENT_POD
  labels:
    app: chaos-client
spec:
  restartPolicy: Never
  terminationGracePeriodSeconds: 1
  containers:
    - name: client
      image: $CLIENT_IMAGE
      command: ["sleep", "86400"]
      resources:
        requests: {cpu: 10m, memory: 32Mi}
        limits: {cpu: 500m, memory: 256Mi}
EOF
  kc wait --for=condition=Ready "pod/$CLIENT_POD" --timeout=120s >/dev/null
}

# pods_ready <app> — exactly one pod with that label, Ready, not terminating.
pods_ready() {
  local out
  out=$(kc get pods -l "app=$1" \
    -o jsonpath='{range .items[*]}{.metadata.deletionTimestamp}|{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}') || return 1
  [[ $out == "|True" ]]
}

stack_healthy() {
  local a
  for a in s3 iam postgres; do
    pods_ready "$a" || return 1
  done
  client_curl -o /dev/null "$S3_URL/healthz" 2>/dev/null &&
    client_curl -o /dev/null "$IAM_URL/healthz" 2>/dev/null
}

# wait_healthy <timeout_s>
wait_healthy() {
  local deadline=$((SECONDS + $1))
  until stack_healthy; do
    ((SECONDS < deadline)) || return 1
    sleep 2
  done
}

iam_token() {
  client_curl -X POST "$IAM_URL/auth/token" -H "Authorization: ApiKey $CHAOS_API_KEY" | jq -r .token
}

setup_identity() {
  local user policy_doc policy
  CHAOS_BUCKET="chaos-$RUN_ID"
  user=$(client_curl -X POST "$IAM_URL/users" -H 'Content-Type: application/json' \
    -d "{\"username\":\"chaos-$RUN_ID\"}")
  CHAOS_USER_ID=$(jq -r .id <<<"$user")
  CHAOS_API_KEY=$(jq -r .apiKey <<<"$user")
  policy_doc=$(jq -nc --arg n "chaos-$RUN_ID" --arg b "$CHAOS_BUCKET" '{
    name: $n,
    document: {statements: [{
      effect: "ALLOW",
      actions: ["s3:CreateBucket", "s3:DeleteBucket", "s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      resources: ["arn:cloudlite:s3:::\($b)", "arn:cloudlite:s3:::\($b)/*"]
    }]}
  }')
  policy=$(client_curl -X POST "$IAM_URL/policies" -H 'Content-Type: application/json' -d "$policy_doc")
  client_curl -X POST "$IAM_URL/users/$CHAOS_USER_ID/policies/$(jq -r .id <<<"$policy")" >/dev/null
  export CHAOS_USER_ID CHAOS_API_KEY CHAOS_BUCKET
  client_curl -X PUT -o /dev/null "$S3_URL/$CHAOS_BUCKET" -H "Authorization: Bearer $(iam_token)"
}

# s3_put_random <key> <bytes> <token> — prints "<status> <sha256>".
s3_put_random() {
  echo "$1" >> "$RUN_DIR/keys"
  client_sh '
    f=$(mktemp)
    head -c "$2" /dev/urandom > "$f"
    s=$(sha256sum "$f" | cut -d" " -f1)
    c=$(curl -s -o /dev/null -w "%{http_code}" --max-time 60 -T "$f" -H "Authorization: Bearer $3" "$1")
    rm -f "$f"
    echo "$c $s"' "$S3_URL/$CHAOS_BUCKET/$1" "$2" "$3"
}

# s3_get_sha256 <key> <token> — prints "<status> <sha256|->".
s3_get_sha256() {
  client_sh '
    f=$(mktemp)
    c=$(curl -s -o "$f" -w "%{http_code}" --max-time 60 -H "Authorization: Bearer $2" "$1")
    if [ "$c" = 200 ]; then s=$(sha256sum "$f" | cut -d" " -f1); else s=-; fi
    rm -f "$f"
    echo "$c $s"' "$S3_URL/$CHAOS_BUCKET/$1" "$2"
}

# In-pod probe loop. Args: timeout_s, stable_run, url, then extra curl args.
# Prints "<epoch_ms> <status>" per request; exits once a failure has been
# seen followed by stable_run consecutive 2xx, or at the timeout.
PROBE_SCRIPT='
end=$(( $(date +%s) + $1 )); need=$2; url=$3; shift 3
seen_fail=0; ok=0
while [ "$(date +%s)" -lt "$end" ]; do
  t=$(date +%s%N)
  c=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "$@" "$url")
  echo "${t%??????} $c"
  case $c in
    2??) ok=$((ok + 1)) ;;
    *) seen_fail=1; ok=0 ;;
  esac
  if [ "$seen_fail" = 1 ] && [ "$ok" -ge "$need" ]; then exit 0; fi
  sleep 0.25
done'

# probe_until_recovered <out_file> <timeout_s> <url> [curl args...]
probe_until_recovered() {
  local out=$1 timeout=$2 url=$3
  shift 3
  client_sh "$PROBE_SCRIPT" "$timeout" "$CHAOS_STABLE_RUN" "$url" "$@" > "$out"
}

kill_pod() { kc delete pod -l "app=$1" --wait=false >/dev/null; }

teardown() {
  set +e
  local jobs_left tok key
  jobs_left=$(jobs -p)
  [[ -n $jobs_left ]] && kill $jobs_left 2>/dev/null
  if [[ -n ${CHAOS_API_KEY:-} ]] && kc get pod "$CLIENT_POD" >/dev/null 2>&1; then
    tok=$(iam_token 2>/dev/null)
    if [[ -n $tok && $tok != null ]]; then
      while read -r key; do
        client_curl -X DELETE -o /dev/null "$S3_URL/$CHAOS_BUCKET/$key" 2>/dev/null
      done < <(sort -u "$RUN_DIR/keys" 2>/dev/null)
      client_curl -X DELETE -o /dev/null "$S3_URL/$CHAOS_BUCKET" 2>/dev/null ||
        log "warn: could not delete bucket $CHAOS_BUCKET"
    else
      log "warn: no IAM token at teardown; bucket $CHAOS_BUCKET left in place"
    fi
  fi
  kc delete pod "$CLIENT_POD" --wait=false --ignore-not-found >/dev/null 2>&1
  if [[ -n ${CHAOS_API_KEY:-} ]]; then
    log "IAM user/policy chaos-$RUN_ID left in place (IAM has no DELETE endpoints)"
  fi
}
```

ShellCheck will flag `kill $jobs_left` (SC2086); word-splitting is intended there — add `# shellcheck disable=SC2086` on the line above it.

- [ ] **Step 4: Implement `chaos/run.sh`**

```bash
#!/usr/bin/env bash
# CloudLite chaos suite. See docs/platform/chaos.md.
# shellcheck source-path=SCRIPTDIR
set -euo pipefail

CHAOS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$CHAOS_DIR/lib/probe.sh"
source "$CHAOS_DIR/lib/report.sh"
source "$CHAOS_DIR/lib/cluster.sh"

usage() {
  cat <<'EOF'
Usage: chaos/run.sh [--context NAME] [--setup-only] [SCENARIO_ID...]

Runs CloudLite chaos scenarios against a k3d cluster and writes a report
to chaos/reports/. With no IDs, runs every scenario in chaos/scenarios/.

  --context NAME   use this kube context (bypasses the k3d-* guard)
  --setup-only     preflight + setup + teardown, no scenarios
  -h, --help       show this help

Exit: 0 all pass (warnings allowed), 1 a scenario failed, 2 aborted.
EOF
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
  trap 'exit 130' INT TERM
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
    set +e
    (
      set -e
      # shellcheck source=/dev/null
      source "$(scenario_file "$id")"
      scenario_run
    )
    rc=$?
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
```

```bash
chmod +x chaos/run.sh
```

- [ ] **Step 5: Run unit tests to verify they pass**

Run: `chaos/test/run-tests.sh`
Expected: `failed: 0`.

- [ ] **Step 6: Lint**

Run: `find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x`
Expected: no output. Fix any finding in the code (don't blanket-disable).

- [ ] **Step 7: Verify the context guard refuses a non-k3d context (live)**

```bash
kubectl config set-context not-k3d --cluster=k3d-cloudlite-test --user=admin@k3d-cloudlite-test
kubectl config use-context not-k3d
chaos/run.sh --setup-only; echo "exit=$?"
kubectl config use-context k3d-cloudlite-test
kubectl config delete-context not-k3d
```
Expected: `refusing to run against context 'not-k3d'…`, `exit=2`, and `kubectl get pod -n cloudlite chaos-client` → NotFound (nothing was created).

- [ ] **Step 8: Run setup-only against k3d (live)**

Run: `chaos/run.sh --setup-only; echo "exit=$?"`
Expected: logs `run <id> against context k3d-cloudlite-test`, `setup ok`, `IAM user/policy chaos-<id> left in place…`; `exit=0`. Afterwards `kubectl get pod -n cloudlite chaos-client` → NotFound or Terminating, and the `chaos-<id>` bucket is gone (`kubectl exec -n cloudlite statefulset/postgres -- psql -U cloudlite -d cloudlite -tAc "select name from buckets where name like 'chaos-%'"` → empty).

- [ ] **Step 9: Verify the busybox assumptions the probe loop depends on (live gate)**

```bash
kubectl run -n cloudlite chaos-gate --rm -i --restart=Never --image=curlimages/curl:8.10.1 --command -- \
  sh -c 't=$(date +%s%N); echo "ms=${t%??????}"; sleep 0.25 && echo sleep-ok; which sha256sum mktemp head'
```
Expected: `ms=` followed by a 13-digit number, `sleep-ok`, and three paths. **If `ms=` is not 13 digits (busybox without nanosecond `date`) or fractional sleep fails: stop and report back** — the probe loop's design depends on both, and the fix (a different client image) is a design decision.

- [ ] **Step 10: Commit**

```bash
git add chaos/lib/cluster.sh chaos/run.sh chaos/test/test_run.sh
git commit -m "feat(chaos): add run harness with context guard, setup and teardown"
```

---

### Task 5: Scenario 01 — kill IAM

**Files:**
- Create: `chaos/scenarios/01-kill-iam.sh`
- Modify: `chaos/test/test_run.sh` (add one test)

**Interfaces:**
- Consumes: `iam_token`, `s3_put_random`, `probe_until_recovered`, `kill_pod`, `now_ms`, `parse_probe`, `kv`, `check_recovery`, `record_result`, `record_metric`, `S3_URL`, `CHAOS_BUCKET`, `RUN_DIR`.
- Produces: `scenario_run` for id `01`; metrics `title`, `window_start_ms`, `window_end_ms`, `recovery_ms_s3`; results `seed`, `fail-closed`, `recovery-s3`, `outage-status`.

- [ ] **Step 1: Add a failing selection test**

Append to `chaos/test/test_run.sh`:
```bash
test_parse_args_selects_named_scenario() {
  parse_args 01
  assert_eq "01" "${SELECTED[*]}" "args: explicit id selects only that scenario"
}
```
Run: `chaos/test/run-tests.sh` — Expected: the run aborts non-zero with `unknown scenario: 01` (the `set -e` test runner stops at the failing `parse_args`).

- [ ] **Step 2: Implement**

`chaos/scenarios/01-kill-iam.sh`:
```bash
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
```

- [ ] **Step 3: Unit tests and lint pass**

Run: `chaos/test/run-tests.sh && find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x`
Expected: `failed: 0`, no lint output.

- [ ] **Step 4: Live run**

Run: `chaos/run.sh 01; echo "exit=$?"`
Expected: report path logged; in the report, `01 Kill IAM` has `fail-closed PASS`, a `recovery-s3` row with a measured time, and an `outage-status` INFO listing `500` (and possibly `000` if requests timed out). `exit=0` if recovery was within 120s. If anything FAILs, read `chaos/reports/raw/<id>/01-s3-get.log` and decide: harness bug ⇒ fix; real service behaviour ⇒ leave it failing, it's a finding.

- [ ] **Step 5: Ctrl-C check (Review Focus 5)**

Run `chaos/run.sh 01`; press Ctrl-C about 5s after `scenario 01` is logged.
Expected: exit status 130; teardown logs appear; within ~5s `kubectl get pod -n cloudlite chaos-client` → NotFound; `pgrep -f "kubectl.*exec.*chaos-client"` → nothing. Then wait for the IAM pod to be Ready again before continuing.

- [ ] **Step 6: Commit**

```bash
git add chaos/scenarios/01-kill-iam.sh chaos/test/test_run.sh
git commit -m "feat(chaos): add scenario 01, kill IAM and verify S3 fails closed"
```
(Don't commit reports from development runs; `git status` should show only `chaos/reports/*.md` as untracked. Delete them.)

---

### Task 6: Scenario 02 — kill S3

**Files:**
- Create: `chaos/scenarios/02-kill-s3.sh`
- Modify: `chaos/lib/cluster.sh` (add `seed_objects`, `check_objects`)

**Interfaces:**
- Produces:
  - `seed_objects <prefix> <count> <bytes> <token> <out_file>` — PUTs `<prefix>-1..N`, writes `"<key> <sha256>"` lines to `out_file`; returns 1 on the first non-200.
  - `check_objects <scenario> <check_name> <seed_file> <token>` — GETs each, records PASS (`N/N objects intact`) or FAIL (lists `key(status)` for mismatches).
  - `scenario_run` for id `02`; results `seed`, `recovery-s3`, `objects-intact`.

- [ ] **Step 1: Add the helpers to `chaos/lib/cluster.sh`** (after `s3_get_sha256`)

```bash
# seed_objects <prefix> <count> <bytes> <token> <out_file>
seed_objects() {
  local prefix=$1 count=$2 bytes=$3 token=$4 out=$5 i res
  : > "$out"
  for ((i = 1; i <= count; i++)); do
    res=$(s3_put_random "$prefix-$i" "$bytes" "$token")
    [[ ${res%% *} == 200 ]] || return 1
    echo "$prefix-$i ${res#* }" >> "$out"
  done
}

# check_objects <scenario> <check_name> <seed_file> <token>
check_objects() {
  local id=$1 name=$2 seed=$3 token=$4 key want got total=0 bad=()
  while read -r key want; do
    total=$((total + 1))
    got=$(s3_get_sha256 "$key" "$token")
    [[ $got == "200 $want" ]] || bad+=("$key(${got%% *})")
  done < "$seed"
  if ((${#bad[@]} == 0)); then
    record_result "$id" "$name" PASS "$total/$total objects read back with matching sha256"
  else
    record_result "$id" "$name" FAIL "$((total - ${#bad[@]}))/$total intact; bad: ${bad[*]}"
  fi
}
```

- [ ] **Step 2: Implement the scenario**

`chaos/scenarios/02-kill-s3.sh`:
```bash
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
```

- [ ] **Step 3: Unit tests and lint pass**

Run: `chaos/test/run-tests.sh && find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x`
Expected: `failed: 0`, no lint output.

- [ ] **Step 4: Live run**

Run: `chaos/run.sh 02; echo "exit=$?"`
Expected: `recovery-s3 PASS` with a measured time, `objects-intact PASS 10/10…`, `exit=0`.

- [ ] **Step 5: Commit**

```bash
git add chaos/lib/cluster.sh chaos/scenarios/02-kill-s3.sh
git commit -m "feat(chaos): add scenario 02, kill S3 and verify objects survive"
```

---

### Task 7: Scenario 03 — kill Postgres

**Files:**
- Create: `chaos/scenarios/03-kill-postgres.sh`

**Interfaces:**
- Consumes: `seed_objects`, `check_objects` (Task 6), plus Task 5's set.
- Produces: `scenario_run` for id `03`; results `seed`, `recovery-s3`, `recovery-iam`, `iam-data`, `objects-intact`.

- [ ] **Step 1: Implement**

`chaos/scenarios/03-kill-postgres.sh`:
```bash
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
```

(`objects-intact` using the *fresh* token also proves the user's policy attachment survived: the GETs are authorized against data IAM re-read from Postgres.)

- [ ] **Step 2: Unit tests and lint pass**

Run: `chaos/test/run-tests.sh && find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x`
Expected: `failed: 0`, no lint output.

- [ ] **Step 3: Live run**

Run: `chaos/run.sh 03; echo "exit=$?"`
Expected: `recovery-s3` and `recovery-iam` rows with measured times, `iam-data PASS`, `objects-intact PASS 10/10…`. If either recovery exceeds 180s, check the raw logs and the services' Hikari reconnect behaviour in Loki before touching the threshold; a real slow reconnect is a finding.

- [ ] **Step 4: Commit**

```bash
git add chaos/scenarios/03-kill-postgres.sh
git commit -m "feat(chaos): add scenario 03, kill Postgres and verify S3/IAM recover"
```

---

### Task 8: Scenario 04 — kill S3 mid-PUT, with blob audit

**Files:**
- Create: `chaos/scenarios/04-kill-s3-mid-put.sh`
- Modify: `chaos/lib/cluster.sh` (add `blob_audit`)

**Interfaces:**
- Produces:
  - `blob_audit` → `"tmp=<n> orphans=<n>"` across S3's whole `/data` vs the `objects.storage_id` column.
  - `scenario_run` for id `04`; results `interrupted-put` (INFO), `stack`, `all-or-nothing`, `retry-put`, `blob-audit`, `coverage` (INFO).

- [ ] **Step 1: Add `blob_audit` to `chaos/lib/cluster.sh`**

```bash
# blob_audit — "tmp=<n> orphans=<n>": leftover temp files, and blob files no
# objects row points at, across S3's whole data dir.
blob_audit() {
  local files ids tmp orphans
  files=$(kc exec deploy/s3 -- ls -1 /data </dev/null)
  ids=$(kc exec statefulset/postgres -- psql -U cloudlite -d cloudlite -tAc 'select storage_id from objects' </dev/null | sort)
  tmp=$(grep -c '\.tmp$' <<<"$files" || true)
  orphans=$(grep -E '^[0-9a-f-]{36}$' <<<"$files" | sort | comm -23 - <(echo "$ids") | grep -c . || true)
  echo "tmp=$tmp orphans=$orphans"
}
```

- [ ] **Step 2: Verify the two exec paths it relies on (live)**

```bash
kubectl exec -n cloudlite deploy/s3 -- ls -1 /data | head -3
kubectl exec -n cloudlite statefulset/postgres -- psql -U cloudlite -d cloudlite -tAc 'select count(*) from objects'
```
Expected: UUID-named files (or nothing on a fresh stack) and a number. If `psql` asks for a password, stop and report back.

- [ ] **Step 3: Implement the scenario**

`chaos/scenarios/04-kill-s3-mid-put.sh`:
```bash
# shellcheck shell=bash
# 04 — Kill S3 during a large PUT. The object must be all-or-nothing afterwards.

scenario_run() {
  local id=04 token url sum put_pid got res audit size=$((80 * 1024 * 1024))
  record_metric "$id" title "Kill S3 mid-PUT"
  token=$(iam_token)
  url="$S3_URL/$CHAOS_BUCKET/s04-big"
  echo s04-big >> "$RUN_DIR/keys"
  sum=$(client_sh 'head -c "$1" /dev/urandom > /tmp/chaos-big && sha256sum /tmp/chaos-big | cut -d" " -f1' "$size")

  record_metric "$id" window_start_ms "$(now_ms)"
  client_sh 'curl -s -o /dev/null -w "%{http_code}" --limit-rate 4M -T /tmp/chaos-big -H "Authorization: Bearer $2" "$1"' \
    "$url" "$token" > "$RUN_DIR/04-put.out" 2>&1 &
  put_pid=$!
  sleep 2
  kill_pod s3
  wait "$put_pid" || true
  record_result "$id" interrupted-put INFO "client saw HTTP $(tr -d '\n' < "$RUN_DIR/04-put.out") (000 = connection dropped mid-transfer)"

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
```

- [ ] **Step 4: Unit tests and lint pass**

Run: `chaos/test/run-tests.sh && find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x`
Expected: `failed: 0`, no lint output.

- [ ] **Step 5: Live run**

Run: `chaos/run.sh 04; echo "exit=$?"`
Expected: `interrupted-put INFO … 000`, `all-or-nothing PASS object absent…`, `retry-put PASS`, `blob-audit PASS` (or WARN with counts — a finding, not a harness bug), `exit=0`.

- [ ] **Step 6: Commit**

```bash
git add chaos/lib/cluster.sh chaos/scenarios/04-kill-s3-mid-put.sh
git commit -m "feat(chaos): add scenario 04, kill S3 mid-PUT with blob audit"
```

---

### Task 9: Full run, first committed report, docs

**Files:**
- Create: `chaos/reports/<first-run>.md`, `docs/platform/chaos.md`
- Modify: `docs/future-work.md`

- [ ] **Step 1: Full run**

Delete any leftover development reports (`rm -f chaos/reports/*.md`), then:
Run: `chaos/run.sh; echo "exit=$?"`
Expected: all four scenarios in the report's summary table, each with a result; windows populated. Open Grafana ("CloudLite Overview") on scenario 01's epoch-ms window and confirm the error-rate spike and recovery are visible — this is the screenshot evidence the interview story needs.

- [ ] **Step 2: Commit the report**

```bash
git add chaos/reports/*.md
git commit -m "docs(chaos): add first full chaos run report"
```

- [ ] **Step 3: Write `docs/platform/chaos.md`**

Follow the shape of `docs/platform/observability.md`:
- **Status:** built, with links to the spec and this plan.
- **Scope:** the four scenarios as a table (inject / measure / pass criteria, copied from the spec §5 table, scenario 04 updated to "80 MiB, `--limit-rate 4M`, kill at ~2s").
- **How to run:** prerequisites (k3d stack per `LOCAL_TESTING_CHECKLIST.md`, `kubectl`, `jq`), `chaos/run.sh [--context NAME] [--setup-only] [IDs…]`, exit codes, where reports and raw logs go.
- **How it works:** in-cluster client pod and why `port-forward` doesn't work; in-pod probe loop; recovery definition (5 consecutive 2xx); context guard.
- **First-run findings:** copy the measured recovery times and every non-PASS/INFO row from the committed report, plus: S3 returns `500 InternalError` instead of `503` when IAM is unreachable (`IamUnavailableException` falls through to the catch-all handler) — to be fixed in a separate `fix(s3)` PR; S3 buffers PUT bodies fully in memory before disk, so scenario 04 only covers kills during transfer.
- **Known limitations:** IAM test users/policies (`chaos-<RUN_ID>`) accumulate because IAM has no DELETE endpoints; assumes the host and the cluster node share a clock (true for k3d).
- **Out of scope:** link to the `future-work.md` entries from Step 4.

- [ ] **Step 4: Update `docs/future-work.md`**

Under "Platform / infra — out of scope for now", add:
```markdown
- Chaos: network-fault injection between S3 and IAM (latency/packet loss
  pushing IAM calls past S3's read timeout) — needs `tc` in a privileged
  container or a chaos operator (Chaos Mesh/Litmus), which costs RAM this
  node doesn't have. Revisit with a second node or a RAM upgrade.
- Chaos: OOM-kill and disk-full scenarios — overlap with resource-limit
  tuning; revisit if a real OOM or full disk shows up in practice.
- Chaos: multipart-upload crash consistency — revisit once S3 multipart
  exists (`docs/services/s3.md`).
```
and under "Revisit triggers":
```markdown
- **S3 multipart upload lands** → extend chaos scenario 04 to kill S3
  mid-multipart (between part uploads and during complete)
```

- [ ] **Step 5: Final verification and commit**

Run: `chaos/test/run-tests.sh && find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x && git status --short`
Expected: `failed: 0`; no lint output; only the two doc files modified/new.

```bash
git add docs/platform/chaos.md docs/future-work.md
git commit -m "docs: add chaos test platform doc and future-work entries"
```
