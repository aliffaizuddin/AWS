# Chaos test

**Status:** built — a repeatable bash suite (`chaos/run.sh`) that kills
IAM, S3, and Postgres pods on a local k3d CloudLite stack, measures
recovery, checks correctness, and writes a Markdown report. See
[`../superpowers/plans/2026-10-06-chaos-test.md`](../superpowers/plans/2026-10-06-chaos-test.md)
for what was built and
[`../superpowers/specs/2026-10-06-chaos-test-design.md`](../superpowers/specs/2026-10-06-chaos-test-design.md)
for the design. First full run:
[`../../chaos/reports/2026-10-06-080820-k3d-cloudlite-test.md`](../../chaos/reports/2026-10-06-080820-k3d-cloudlite-test.md).

## Scope

Every component is single-replica, so every kill causes real downtime by
design. The suite measures *recovery time* and *correctness*, not
availability.

| # | Inject | Measure | Pass if |
|---|---|---|---|
| 01 Kill IAM | probe S3 `GET` and IAM `/healthz` every 250ms, delete the IAM pod | recovery time for S3 and IAM | fail-closed: no 2xx after S3's first error, and S3 serves stable 2xx no earlier than IAM was known down — the later of IAM's last fast refusal and (IAM's first stable 2xx − the 2s probe timeout), 500ms tolerance; WARN if neither is known; recovery < 120s |
| 02 Kill S3 | seed 10 × 64 KiB objects, probe `GET`, delete the S3 pod | recovery time | recovery < 120s; all 10 objects read back with identical sha256 |
| 03 Kill Postgres | same seed; probe S3 `GET` and IAM `/auth/token` concurrently, delete `postgres-0` | recovery time for S3 and IAM separately | both < 180s; objects intact; the test user's API key still mints a token that is still authorized |
| 04 Kill S3 mid-PUT | 80 MiB `PUT` throttled with `curl --limit-rate 4M`, delete the S3 pod ~2s in | — | object afterwards is absent or byte-identical (never partial); retried `PUT` succeeds; leftover `.tmp` files / orphaned blobs reported (WARN, not FAIL) |
| 05 Kill S3 mid-multipart | upload parts 1–2 (5 MiB), kill S3 during part 3; later kill S3 right after firing a complete | — | the upload is rediscovered via ListMultipartUploads; parts 1–2 survive with their ETags; the resumed upload completes with the expected multipart ETag and byte-identical content; a complete interrupted by the kill can be retried and returns the same ETag |

"Recovered" means the first of 5 consecutive 2xx responses after the
outage began.

## How to run

Prerequisites: a k3d cluster running the `cloudlite` chart (s3, iam and
postgres Ready), plus `kubectl`, `jq` and `git` on the host. Linux host
(GNU `date`).

```bash
chaos/run.sh                  # all scenarios
chaos/run.sh 01 03            # a subset
chaos/run.sh --setup-only     # preflight + setup + teardown, no kills
chaos/run.sh --context NAME   # use a non-k3d context (bypasses the guard)
chaos/test/run-tests.sh       # offline unit tests, no cluster
```

Exit codes: `0` all PASS (WARN allowed) · `1` any FAIL · `2` preflight,
context-guard, setup, or infrastructure abort · `130` Ctrl-C · `143`
SIGTERM. On either signal the running scenario's whole process tree
(probe loops, `kubectl exec`s) is stopped immediately, then teardown
runs.

Output: `chaos/reports/<YYYY-MM-DD-HHMMSS>-<context>.md` (commit the ones
worth keeping) and raw probe logs under `chaos/reports/raw/<run-id>/`
(gitignored). Each scenario's window is in the report in UTC and epoch
ms — paste the epoch-ms range into Grafana's time picker on the
"CloudLite Overview" dashboard to see the outage.

## How it works

- **In-cluster client pod.** `kubectl port-forward svc/...` pins to one
  pod and dies with it, so it can't measure recovery from pod kills. The
  suite creates a throwaway `chaos-client` pod (`curlimages/curl`) and
  sends all traffic from inside the cluster to the Service names
  (`http://s3:8080`, `http://iam:8081`) — the same path S3 uses to reach
  IAM.
- **Probe loop.** One `kubectl exec` per probe session runs a `sh` loop
  in the client pod (a request every 250ms, `--max-time 2`), printing one
  HTTP status per line. The host prefixes each line with epoch ms as it
  arrives (`stamp_lines`), because busybox `date` in the client image has
  no sub-second resolution. Probe timestamps and the kill timestamp
  therefore share the host clock.
- **Context guard.** Refuses any kube context that doesn't start with
  `k3d-` unless `--context` is passed explicitly.
- **Identity.** Each run creates its own IAM user, policy and bucket
  named `chaos-<run-id>` through IAM's open admin endpoints.
- **Between scenarios** the suite waits (up to 300s) for s3/iam/postgres
  to be Ready with `/healthz` 200; if they aren't, it aborts the remaining
  scenarios.

## First-run findings (2026-10-06, k3d)

All four scenarios PASS. Measured recovery: IAM kill → IAM healthy in
11.4s and S3 serving again at 12.4s (never before IAM); S3 kill → 14.2s;
Postgres kill → IAM 12.7s, S3 18.9s.

- **S3 failed closed when IAM was down, but returned `500 InternalError`
  instead of `503`.** `IamUnavailableException` had no dedicated handler
  in `GlobalExceptionHandler`, so it fell through to the catch-all.
  Fixed: S3 now returns `503 ServiceUnavailable` (retryable for S3
  clients) and logs at WARN instead of an ERROR stack trace per request.
  Requests that hit S3 while it is waiting on IAM's TCP connect also
  time out client-side (`000`).
- **An interrupted `PUT` leaves nothing behind.** S3 buffers the whole
  request body in memory before touching disk, so a kill mid-transfer can
  only leave the object absent; no `.tmp` files or orphaned blobs were
  found. The consequence is that scenario 04 does **not** exercise the
  window between the blob's atomic rename and the metadata insert, where
  an orphaned blob is possible (S3 already logs that case).
- **The Grafana error-rate panel originally showed nothing at all.**
  Micrometer creates a `status="500"` counter lazily, on the first 500.
  A ~12s outage is over before the next 15s scrape, so Prometheus's
  first sample of that series already holds the final count and
  `rate()` sees no increase — the panel stayed at 0 for the whole run
  even though `http_server_requests_seconds_count` recorded every
  failure. Fixed by changing the panel's query to treat a series that
  is new within the window as starting from zero (see
  [`observability.md`](observability.md), "Lazily-created counters").

### Multipart run (2026-10-06, k3d)

All five scenarios PASS
([report](../../chaos/reports/2026-10-06-080820-k3d-cloudlite-test.md)).
Scenario 05 found a real bug on its first run: **complete returned
`MalformedXML` for `curl`'s default form-encoded body**, because any
`getParameter()` call (Spring's `params` routing condition, the auth
interceptor) makes Tomcat parse a form-encoded POST body into
parameters, leaving the controller nothing to read. Unit tests missed it
— MockMvc never parses bodies. Fixed by routing POST and checking
multipart parameters from the raw query string; pinned by a real-Tomcat
integration test.

The same run also exposed a **measurement flaw in 01's fail-closed
check**: the IAM probe is sequential with a 2s timeout, so one request
routed to the terminating IAM pod blinded it for ~2s, and IAM's first
2xx lagged S3's. A timed-out sample says nothing about IAM; a fast
refusal proves it was down, and so does the gap before IAM's first 2xx
minus one probe timeout. The check now orders S3's recovery against the
later of those two (`down_until_ms`, and recovery − 2s) instead of IAM's
first success — so a fail-open during an IAM timeout phase still FAILs.

## Known limitations

- **IAM test users/policies accumulate** — IAM has no DELETE endpoints
  for users or policies, so each run leaves a `chaos-<run-id>` pair
  behind (logged at teardown). Buckets and objects are deleted.
- **Interrupting a run while IAM or S3 is down** (Ctrl-C/SIGTERM during
  a kill) leaves that run's bucket behind: teardown can't get a token or
  reach S3 to delete it. Teardown logs a warning; the client pod is still
  removed.
- A probe that produces no samples (e.g. `kubectl exec` failed) is a
  FAIL, never a WARN or a recovery time.
- Assumes the host running `chaos/run.sh` and the cluster share a clock
  closely enough for millisecond timing — true for k3d.

## Out of scope

Network-fault injection, OOM/disk-full scenarios, and multipart-upload
crash consistency — see [`../future-work.md`](../future-work.md). Running
chaos in CI (CI only runs ShellCheck and the offline unit tests:
`.github/workflows/ci-chaos.yml`). Running against the real bare-metal
node.
