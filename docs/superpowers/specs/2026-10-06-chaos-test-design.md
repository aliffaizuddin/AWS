# Chaos Test (Platform Layer, Part 6) — Design

Date: 2026-10-06
Status: Approved, not yet planned/implemented

## 1. Context

Per `architecture.md` §11, platform-layer step 4's sub-projects are
complete and merged except this one: Helm charts (PR #13), CI/CD
(PR #18), ArgoCD GitOps + Sealed Secrets (PR #20), and observability
(PR #21). The chaos test is the last piece of step 4, and the source of
the SRE half of the interview narrative in `architecture.md` §12:
"here's the chaos test I ran, here's the recovery time" / "here's what
happens when the IAM service goes down and S3 starts failing auth
checks."

Facts about the current system that shape this design:

- **Everything is single-replica** (S3, IAM, Postgres). Killing any pod
  causes real downtime by design on a single-node cluster. The chaos
  test therefore measures *recovery time* and *correctness*
  (fail-closed auth, no data loss), not availability.
- **S3 fails closed when IAM is unreachable.** `IamClient.authorize`
  wraps any `RestClientException` in `IamUnavailableException`.
- **That failure surfaces as `500 InternalError`, not `503`.**
  `GlobalExceptionHandler` has no handler for `IamUnavailableException`,
  so it falls through to the catch-all `Exception` handler. Still
  fail-closed, but the status code is misleading. Recorded as a finding;
  the fix is a separate `fix(s3)` PR (see §3).
- **S3 has no multipart upload yet** (`docs/services/s3.md`: Phase 1, "no
  ranges/versioning/multipart yet"). The crash-consistency scenario
  therefore targets a single large `PUT`.
- **S3's `PUT` ordering** (`ObjectService.put` → `DiskBlobStore.put`):
  buffer body in memory → write blob to a `.tmp` file + `fsync` →
  `ATOMIC_MOVE` rename into place → upsert metadata row in Postgres →
  delete superseded blob. A kill can never expose a partial object; a
  kill between rename and metadata save leaves an **orphaned blob**
  (already logged by the code as a known case). Max object size is
  100 MiB.
- **`kubectl port-forward` is pod-bound.** The local checklist reaches
  S3/IAM via `port-forward svc/...`, which pins to one pod and dies with
  it — unusable for measuring recovery from pod kills.
- IAM-issued JWTs expire after 900s by default
  (`IAM_JWT_EXPIRY_SECONDS`).
- Pod labels: `app=s3`, `app=iam`, `app=postgres`, all in namespace
  `cloudlite`. S3 stores blobs under `/data` (PVC, `bulk-hdd`).

Validation target, as for every prior platform sub-project: a local k3d
cluster stood up per `LOCAL_TESTING_CHECKLIST.md`, not the user's real
bare-metal node.

## 2. Goals

- A **repeatable, scripted** chaos suite the user can re-run at will,
  producing a committed Markdown report with real recovery numbers and
  per-check pass/fail.
- Four scenarios:
  1. Kill IAM — S3 fails closed during the outage; measure recovery.
  2. Kill S3 — measure recovery; previously written objects survive.
  3. Kill Postgres — S3 and IAM both recover; no data loss.
  4. Kill S3 mid large `PUT` — object is all-or-nothing; metadata and
     disk agree (orphans reported).
- Every scenario records start/end timestamps so the matching window can
  be opened in the existing Grafana "CloudLite Overview" dashboard for
  screenshots.
- Safe by default: refuses to run against anything but a `k3d-*`
  context unless explicitly told otherwise.

## 3. Non-goals

- **Network-fault injection** (latency/packet loss between S3 and IAM)
  — needs `tc` in a privileged container or a chaos operator (Chaos
  Mesh/Litmus); added to `future-work.md`.
- **OOM / disk-full scenarios** — fiddly, overlap with resource-limit
  tuning; added to `future-work.md`.
- **Multipart crash-consistency** — multipart doesn't exist yet; added
  to `future-work.md` with "once multipart lands" as the trigger.
- **Fixing S3's 500-vs-503** on IAM unavailability — separate small
  `fix(s3)` PR, referenced from the report.
- **Orphaned-blob garbage collection** — reported as a finding, not
  fixed here.
- **Running chaos in CI** against an ephemeral cluster — CI only lints
  the scripts.
- Chaos operators of any kind (Chaos Mesh, Litmus) — no new in-cluster
  components; RAM budget unchanged.

## 4. Architecture

Bash + `kubectl` + `curl` + `jq`, chosen over a Java (Fabric8) or Go
(client-go) harness: every operation is a visible `kubectl`/`curl` call,
which suits a platform demo; nothing to build; and four scenarios fit
comfortably in bash.

```
chaos/
  run.sh              # entry point: preflight → setup → scenarios → report → teardown
  lib.sh              # shared helpers
  scenarios/
    01-kill-iam.sh
    02-kill-s3.sh
    03-kill-postgres.sh
    04-kill-s3-mid-put.sh
  reports/            # committed, timestamped Markdown reports
    raw/              # gitignored raw probe logs, one dir per run
```

Usage: `./chaos/run.sh` (all scenarios) or `./chaos/run.sh 01 03`
(subset). `--context <name>` overrides the context guard.

### Reaching the services: in-cluster client pod

At setup, the suite creates a `chaos-client` pod (`curlimages/curl`,
pinned tag, `sleep infinity`) in namespace `cloudlite`. All traffic to
S3/IAM goes from that pod to the Service DNS names (`http://s3:8080`,
`http://iam:8081`) — the same path S3 uses to reach IAM, and unaffected
by pod restarts.

**Probe loops run inside the client pod**, in a single `kubectl exec`
per probe session: a `sh` loop issuing one request every 250ms and
printing `<epoch_ms> <http_status>` per line, until a stop condition or
a hard timeout. The host script captures the output to the raw log and
parses it afterwards. This keeps measurement resolution independent of
`kubectl exec` startup overhead (~100ms+). `curl` uses
`--max-time 2` per request so a hung connection costs at most one
probe slot.

### Run flow

1. **Context guard** — current context must start with `k3d-`, else
   exit 2 unless `--context` was passed and matches.
2. **Preflight** — `jq`, `kubectl` on PATH; pods `app=s3`, `app=iam`,
   `app=postgres` Ready; `/healthz` 200 on S3 and IAM (via client pod).
   Any failure → exit 2.
3. **Setup** — create client pod; via IAM's open admin endpoints create
   user `chaos-<runid>`, a policy allowing
   `s3:CreateBucket|DeleteBucket|PutObject|GetObject|DeleteObject`
   (the action names S3's `AuthInterceptor` actually emits) on
   `arn:cloudlite:s3:::chaos-<runid>` and `.../*`, attach it; create
   bucket `chaos-<runid>`. `runid` = `YYYYMMDD-HHMMSS`.
4. **Scenarios**, in order. Each: fetch a fresh JWT (avoids the 900s
   expiry over a long run), baseline, inject, measure, check, then
   **wait-for-healthy** (all three pods Ready + both `/healthz` 200,
   timeout 5 min) before the next. Wait-for-healthy timing out is an
   infrastructure error → abort remaining scenarios, exit 2.
5. **Report** — write Markdown report (§5, Report).
6. **Teardown** (`trap` on EXIT/INT/TERM, always runs) — delete test
   objects and bucket (best effort), detach/delete policy and user
   (best effort), delete client pod.

## 5. Components

### `lib.sh`

- `log`/`die` — timestamped stderr logging.
- `now_ms` — epoch milliseconds.
- `kc` — `kubectl -n cloudlite --context "$CTX"` wrapper; every kubectl
  call goes through it so the context guard can't be bypassed.
- `client_exec` — `kc exec chaos-client -- sh -c ...`.
- `iam_token` — `/auth/token` with `Authorization: ApiKey ...` → JWT.
- `s3_put`/`s3_get_sha256` — object PUT and GET-and-hash via the client
  pod.
- `probe_until_recovered <url> <timeout_s>` — runs the in-pod probe
  loop; stops after 5 consecutive 2xx; emits the raw lines.
- `parse_probe` — from raw lines + kill timestamp, derives: first
  non-2xx after kill (outage start), first of the final 5-consecutive-2xx
  run (recovery point), recovery time = recovery point − kill time, and
  whether any 2xx appeared between outage start and recovery (used by
  scenario 01's fail-closed check).
- `wait_healthy <timeout_s>` — pods Ready + `/healthz` checks.
- `result` — records `scenario|check|PASS/FAIL/WARN|detail` lines to a
  run-level results file the report is built from.

### Scenarios

| # | Inject | Measure | Pass criteria |
|---|---|---|---|
| 01 Kill IAM | Seed one object. Start probe of `GET` on it (Bearer JWT), then `kc delete pod -l app=iam --wait=false` | outage start, recovery time; observed status codes during outage | **no 2xx between outage start and recovery** (fail-closed); recovery < 120s. Observation: the non-2xx status(es) seen (expected today: 500) |
| 02 Kill S3 | Seed 10 objects (random 64 KiB each, sha256 recorded). Probe `GET` on one; `kc delete pod -l app=s3 --wait=false` | recovery time | recovery < 120s; all 10 objects read back with identical sha256 |
| 03 Kill Postgres | Same 10-object seed. Two concurrent probes: S3 `GET` and IAM `/auth/token`. `kc delete pod -l app=postgres --wait=false` | recovery time for S3 and IAM separately | both recover < 180s; all 10 objects match; test user's API key still mints a token and that token is still authorized for `GetObject` (IAM data intact) |
| 04 Kill S3 mid-`PUT` | Generate 80 MiB random file in the client pod, record sha256. Start `PUT` throttled with `curl --limit-rate` (so transfer takes ~20s); after ~2s, `kc delete pod -l app=s3 --wait=false` | — | after recovery, `GET` of the key is either `404 NoSuchKey` or returns the exact sha256 (**never partial/corrupt**) — FAIL otherwise; a retried `PUT` succeeds and reads back identical — FAIL otherwise; count of `*.tmp` files and blobs under `/data` with no metadata row — **WARN** if > 0, with counts |

Notes:

- Scenario 03's 180s threshold allows for Postgres restart plus both
  services' connection pools reconnecting. All thresholds are starting
  points; the report always records actual measured times, and the
  first real run may adjust them.
- Scenario 04's orphan count: list `/data` via `kc exec deploy/s3 -- ls`
  (S3 image is `eclipse-temurin`-based, has a shell) and compare
  against storage IDs from
  `kc exec postgres-0 -- psql -tAc "select storage_id from ..."`. The
  exact table/column names are confirmed against S3's Flyway
  migrations in the implementation plan.
- The 80 MiB body is buffered fully in S3's memory by `ObjectService`;
  this fits within S3's current memory limit, verified in the plan.

### Report (`chaos/reports/YYYY-MM-DD-HHMM-<context>.md`)

- Header: run ID, kube context, git SHA (`git rev-parse --short HEAD`),
  image tags of s3/iam/postgres pods.
- Summary table: scenario, PASS/FAIL/WARN, recovery time(s), window
  start/end in UTC and epoch ms (paste-ready for Grafana's time picker).
- Per-scenario section: each check with result and detail, plus
  observations (e.g. "IAM-down status: 500 InternalError").
- Raw probe logs: `chaos/reports/raw/<runid>/` (gitignored).
- Reports are not auto-committed; the user commits the ones worth
  keeping.

### Exit codes

`0` all PASS (WARN allowed) · `1` any scenario FAIL · `2` preflight,
context-guard, or infrastructure abort.

### CI: `.github/workflows/ci-chaos.yml`

Triggers on PRs/pushes touching `chaos/**`. Single job: `shellcheck`
over `chaos/**/*.sh`. No cluster.

## 6. Error handling

- All scripts: `set -euo pipefail`.
- Teardown via `trap` always runs, best-effort per step (one failing
  cleanup step doesn't skip the rest).
- Check failures are recorded via `result` and the scenario continues
  to its remaining checks; only infrastructure errors (preflight,
  wait-for-healthy timeout, client pod lost) abort the run.
- Probe loops have hard timeouts (scenario threshold + 60s) so a
  never-recovering service yields a FAIL with "did not recover within
  Ns", not a hang.

## 7. Testing

- **Static:** `shellcheck` locally and in `ci-chaos.yml`.
- **Real:** one full run against local k3d stood up per
  `LOCAL_TESTING_CHECKLIST.md`, producing the first committed report.
  That report — including any FAILs or WARNs it surfaces — is the
  sub-project's deliverable. A FAIL that reflects a real service defect
  is a finding to document (and, if small, fix in a separate PR), not a
  reason to loosen the check.
- **Guard check:** confirm the context guard refuses a non-`k3d-*`
  context (simulate with a renamed context or `--context` mismatch).

## 8. Docs

- New `docs/platform/chaos.md`: status, scope, how to run, scenarios
  table, first-run findings (recovery numbers, 500-vs-503, orphan
  counts), out of scope.
- `docs/future-work.md`: add network-fault injection, OOM/disk-full
  scenarios, and multipart crash-consistency, each with a revisit
  trigger.
- `.gitignore`: `chaos/reports/raw/`.

## 9. Open items for the implementation plan

- Exact S3 metadata table/column names for the orphan check (from
  Flyway migrations).
- Confirm S3's memory limit comfortably holds an 80 MiB buffered body;
  lower to a smaller size (e.g. 50 MiB) if not.
- Pin the `curlimages/curl` tag; confirm it ships `sha256sum` and `head`
  (busybox) for in-pod hashing and random-file generation, else
  generate the file differently.
- Confirm `kubectl exec` into the S3 container works (shell + `ls`
  available in the runtime image stage, not just the build stage).
