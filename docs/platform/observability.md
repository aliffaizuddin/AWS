# Observability (Prometheus + Grafana + Loki)

**Status:** built — Prometheus, Loki (single-binary), Grafana Alloy, and
Grafana added as trimmed, resource-budgeted subcharts to
`deploy/helm/cloudlite`, scraping/shipping real metrics and logs from S3
and IAM. See
[`../superpowers/plans/2026-09-02-observability.md`](../superpowers/plans/2026-09-02-observability.md)
for what was built and
[`../superpowers/specs/2026-09-02-observability-design.md`](../superpowers/specs/2026-09-02-observability-design.md)
for the design.

## Scope

- `services/s3`, `services/iam` — Spring Boot Actuator +
  `micrometer-registry-prometheus`, exposing `/actuator/prometheus`
  (auto HTTP + JVM metrics, tagged `application=s3`/`application=iam`,
  with histogram buckets enabled for `histogram_quantile`). Both services'
  logging switched to JSON via `logstash-logback-encoder`.
- `prometheus` subchart — scrapes both services via pod
  `prometheus.io/scrape` annotations (the chart's built-in
  `kubernetes-pods` job, no custom scrape config needed). Alertmanager,
  kube-state-metrics, node-exporter, and pushgateway all disabled — not
  part of this project's story. `bulk-hdd`-backed PVC.
- `loki` subchart — single-binary mode, filesystem storage, `bulk-hdd`-backed
  PVC. Gateway and canary disabled.
- `alloy` subchart — one DaemonSet pod (single-node cluster) tailing
  `/var/log/pods` via a hand-written River pipeline, pushing to Loki. No
  log parsing at ship time — structured JSON fields are parsed at query
  time in Grafana via LogQL `| json`.
- `grafana` subchart — datasources (Prometheus, Loki) and one dashboard
  ("CloudLite Overview": request rate, error rate, p99 latency, logs) both
  provisioned via labeled `ConfigMap`s, no manual setup. Admin credentials
  via a new `grafana-admin` `SealedSecret`, same pattern as
  `postgres-credentials`/`iam-jwt-secret`.
- No new bootstrap step — these are ordinary umbrella-chart subcharts,
  picked up automatically by the existing ArgoCD `Application` on its next
  sync, unlike ArgoCD/Sealed Secrets' own chicken-and-egg install.

## A real gotcha this sub-project found and fixed

S3's `AuthWebMvcConfigurer` gates every path except `/healthz`/`/error`
behind a valid IAM-issued JWT. Without excluding `/actuator/**`, every
Prometheus scrape against S3 would have 403'd. IAM has no equivalent
interceptor, so needed no such change.

## Validated against

A local k3d cluster (real k3s in Docker), the same sandbox stand-in used
for every prior platform sub-project. Confirmed for real: Prometheus
scrapes both services with real `up=1` targets; Grafana's datasources and
dashboard *object* exist via the API and render with zero manual
configuration; Loki holds real, JSON-structured log lines shipped by
Alloy; Prometheus's and Loki's PVCs are `bulk-hdd`-backed; and — after
the final-fix-wave re-verification that widened the dashboard's `[1m]`
rate windows to `[5m]`, added `global.scrape_interval: 15s`, and inserted
a `loki.process` / `stage.cri` step to strip the CRI log envelope ahead
of Loki's `| json` parsing — each dashboard panel's *actual query* now
also returns real, non-empty data against a live cluster: request-rate
and error-rate `rate(...)` queries return non-null per-application
series, the p99 `histogram_quantile` query returns a real value, and the
Logs panel's `{namespace="cloudlite", container=~"s3|iam"} | json` query
returns lines with successfully-parsed JSON fields (no `__error__`). See
`.superpowers/sdd/2026-09-02-observability/final-fix-report.md` for the
actual query responses. Earlier validation (through Task 8) had only
confirmed the dashboard *object* existed, not that its panel queries
returned data — that gap is what this fix wave closed.

## Known operational properties (not defects)

- **Loki single-replica replication factor:** Loki's Helm chart defaults
  `commonConfig.replication_factor` to 3 regardless of the actual replica
  count specified in `singleBinary.replicas`. On a single-replica
  deployment (the only viable mode on a single-node cluster), this causes
  every Loki query to fail with a permanent ring-quorum error (cannot
  achieve replication factor 3 with 1 replica). Fixed by explicitly
  setting `replication_factor: 1` in the values override. This is a
  real operational gotcha for single-binary Loki deployments — static
  `helm lint`/`helm template` validation would not have caught it.
- **Alloy glob expansion for `loki.source.file`:** Grafana Alloy's
  `loki.source.file` component does not expand glob patterns in its
  `__path__` argument. If fed a raw glob pattern (e.g.,
  `/var/log/pods/*/container.log`), Alloy silently treats it as a
  literal path, ships zero logs forever, and exhibits no indication of
  failure in its own health/readiness status. Fixed by inserting a
  `local.file_match` component ahead of `loki.source.file` to expand
  the glob pattern and yield concrete file paths. Ensure any Alloy
  configuration tailing pod logs explicitly expands the glob first.
- **Re-sealing on cluster change:** same as `postgres-credentials`/
  `iam-jwt-secret` — `grafana-admin`'s `SealedSecret` ciphertext is bound
  to the sealing cluster's key. Pointing this chart at a different cluster
  means re-sealing all three secrets, not just this new one.
- **`helm dependency build` is now required** for the umbrella chart
  before any `lint`/`template`/`install` from a fresh checkout — the
  `s3`/`iam` subcharts didn't need this (vendored as source), but
  `prometheus`/`loki`/`alloy`/`grafana` are pulled from remote repos.
  `.github/workflows/ci-helm.yml` was updated to run it; a human running
  `helm` locally needs `helm repo add prometheus-community ...` /
  `helm repo add grafana ...` first (see Global Constraints in the plan).
  ArgoCD's own repo-server needs the same thing at sync time: it now
  needs network egress to `prometheus-community.github.io` and
  `grafana.github.io` to run `helm dependency build` during a sync,
  which wasn't true before this sub-project (the pre-existing `s3`/`iam`
  subcharts are vendored source, not remote deps).
- **Loki's memcached sidecars OOM this project's target hardware:** the
  Loki chart defaults `resultsCache.enabled`/`chunksCache.enabled` to
  `true`, sizing both memcached sidecars for production HA (~11GB
  combined) — far more than this project's actual 12GB bare-metal target
  (`docs/architecture.md` §2, §8) can spare, and enough to OOM a
  validation cluster in practice. Fixed by disabling both at the
  `values.yaml` level (`loki.resultsCache.enabled: false` /
  `loki.chunksCache.enabled: false`); SingleBinary mode works fine
  without them at this project's scale.
- **`loki.useTestSchema: true`:** used so this deployment doesn't need a
  hand-authored schema config — it's upstream's own documented shortcut
  for filesystem-storage deployments, and was verified (via rendered
  output) to still resolve `object_store: filesystem`, not ephemeral
  storage. It's marked test-only upstream, though, so a future chart
  version bump should be checked against this still working as expected.
- **`loki.test.enabled: false`:** disables the Loki chart's own bundled
  `test`-hook Pod, for the same reason `grafana.testFramework.enabled` is
  now also set to `false` (see the final-fix-wave report referenced
  above): Helm skips a bare `test` hook on `install`/`upgrade`, but
  ArgoCD does not recognize that hook type (only `pre/post-install`,
  `pre/post-upgrade`, `post-delete`), so it would otherwise sync as an
  ordinary always-present Pod/ServiceAccount/ConfigMap that can flip the
  whole Application to Degraded if it ever exits non-zero.

## Out of scope

Custom application metrics (e.g. an IAM allow/deny counter) — deferred,
auto-instrumentation was sufficient for this sub-project. Alerting/
Alertmanager — no on-call story for a single-operator project.
`node-exporter`/`kube-state-metrics` — cluster/node-level metrics aren't
this sub-project's story. Long-term retention tuning. TLS/ingress exposure
of Grafana or Prometheus. Chaos test (separate, later sub-project — this
sub-project is its direct prerequisite). Actually running this against the
user's real bare-metal node.
