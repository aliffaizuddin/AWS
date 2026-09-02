# Prometheus + Grafana + Loki Observability (Platform Layer, Part 5) — Design

Date: 2026-09-02
Status: Approved, not yet planned/implemented

## 1. Context

Per `architecture.md` §11, platform-layer step 4's first three
sub-projects are complete and merged: Helm charts (PR #13), CI/CD
(PR #18), and ArgoCD GitOps + Sealed Secrets (PR #20). The GitOps loop
described in `architecture.md` §7 (`Git repo → CI pipeline → Image
registry → ArgoCD → k3s cluster`) is real and validated end-to-end.

Per the brainstorming skill's guidance, step 4 was decomposed (see
`docs/superpowers/specs/2026-08-24-argocd-design.md` §1):

1. Helm charts — done (PR #13)
2. k3s node provisioning (Terraform/Ansible) — the user's own hardware
3. CI/CD — done (PR #18)
4. ArgoCD GitOps — done (PR #20)
5. **Observability (Prometheus + Grafana + Loki)** — this document
6. Chaos test

**A scope-widening fact surfaced during brainstorming, not a
workaround:** neither S3 nor IAM currently exposes any metrics
endpoint or emits structured logs, despite `architecture.md` §11
stating "structured (JSON) logging... from line one" as an intended
principle. Both services use plain Spring Boot defaults — default
Logback text-to-stdout, no Actuator, no Micrometer, only the
hand-rolled `/healthz`. Deploying Prometheus/Grafana/Loki against that
would have nothing app-level to show. This sub-project therefore
covers both the code-side instrumentation and the platform-side stack
as one unit, matching how the S3→IAM wiring sub-project was scoped.

ADR [0009](../../decisions/0009-plg-observability-stack.md) already
settled PLG over ELK on resource-budget grounds (~1-1.5GB combined vs.
ELK's ~5-7GB); this document is the concrete design for actually
building it.

**Environment note:** validated against a local k3d cluster (real k3s
running in Docker), the same sandbox stand-in used for every prior
platform sub-project — this session cannot reach the user's physical
hardware.

## 2. Goals

- `spring-boot-starter-actuator` + `micrometer-registry-prometheus`
  added to both `services/s3/pom.xml` and `services/iam/pom.xml`,
  exposing `/actuator/prometheus` (auto-instrumented HTTP request
  metrics — rate/latency/status per endpoint — and JVM metrics —
  heap, GC, threads). No custom app-specific metrics (e.g. an IAM
  allow/deny counter) in this sub-project — auto-instrumentation is
  enough to prove the chaos-test story (request rate/error rate/
  latency visibly changing when a dependency goes down) without
  touching `policy/` or other app logic.
- Both services' logging switched from Logback's default plain-text
  encoder to a JSON encoder (`logstash-logback-encoder`, the standard
  Spring Boot pairing) via `logback-spring.xml`, so every log line is
  a structured JSON object with `level`, `logger`, `message`, `thread`,
  and MDC fields.
- Existing hand-rolled `/healthz` is untouched — Actuator's own
  `/actuator/health` is additive, not a replacement; k8s
  readiness/liveness probes keep pointing at `/healthz`.
- Four new Helm chart dependencies added to
  `deploy/helm/cloudlite/Chart.yaml`, alongside the existing `iam` and
  `s3` subcharts:
  - `prometheus` (prometheus-community/prometheus) — scrapes
    `/actuator/prometheus` on S3 and IAM via pod
    `prometheus.io/scrape` annotations, `bulk-hdd` `StorageClass` for
    its TSDB (per `architecture.md` §4, which already earmarks HDD for
    "Prometheus/Loki long-term retention").
  - `loki` (grafana/loki, `singleBinary` deployment mode) — log
    storage, also on `bulk-hdd`.
  - `alloy` (grafana/alloy) as a DaemonSet — tails container logs on
    the node, pushes to Loki. No pipeline-stage JSON parsing in Alloy
    itself; logs ship as raw structured lines and get parsed at query
    time in Grafana via LogQL `| json` (Loki's recommended pattern —
    keeps the shipper config simple, keeps label cardinality low).
  - `grafana` (grafana/grafana) — dashboards + Explore UI. Both
    datasources (Prometheus, Loki) and one dashboard (per §5) are
    provisioned via ConfigMaps mounted into well-known sidecar-watched
    paths — config as code, not hand-clicked, matching this project's
    GitOps philosophy. Admin password via a new `SealedSecret`
    (`grafana-admin`), same pattern as `postgres-credentials` and
    `iam-jwt-secret`.
- All four new components get explicit
  `resources.requests/limits`, same discipline as every prior
  platform sub-project — no component ships resource-unbounded.
- **No new bootstrap step.** Unlike ArgoCD/Sealed Secrets (which had a
  chicken-and-egg problem — nothing exists yet to GitOps-manage their
  own install), this stack is ordinary umbrella-chart subcharts. The
  existing single ArgoCD `Application`
  (`deploy/argocd/applications/cloudlite.yaml`) picks it up
  automatically on its next sync once `Chart.yaml`/`values.yaml` land
  on `main` — no manual `kubectl apply` sequence like §4 of the ArgoCD
  design doc needed.
- `docs/architecture.md` §8's capacity budget table gets four new
  rows (§6 below).
- Validated end-to-end against the real k3d cluster: `/actuator/prometheus`
  scraped and visible as real time series in Prometheus; a real log
  line from an S3 request visible in Loki via Grafana Explore,
  filterable by `level`/`logger`; the provisioned dashboard (§5)
  showing live, non-zero panels while driving real traffic through
  S3/IAM.

## 3. Non-goals

- Custom application metrics (IAM allow/deny counter, S3
  bucket/object-count gauges, etc.) — deferred; auto-instrumentation
  is sufficient for this sub-project's goals per §2. A clean future
  add-on once there's a concrete interview reason to want it.
- Alertmanager, alerting rules, or paging — no on-call story for a
  single-operator resume project; `future-work.md`-worthy, not blocking
  this sub-project's dashboards from being real.
- `node-exporter` / `kube-state-metrics` — cluster/node-level metrics
  aren't part of this sub-project's story (app-level request behavior
  + logs is); the `kube-prometheus-stack` meta-chart bundling these
  was considered and rejected in favor of trimmed per-component charts
  (see brainstorming discussion — heaviest option, hardest to fit the
  ADR 0009 budget).
- Long-term retention tuning / storage capacity planning for
  Prometheus or Loki beyond a sane default (e.g. 15d) — this is a
  single-node resume cluster, not a production retention policy;
  revisit if `future-work.md` ever gains a trigger for it.
- TLS/ingress exposure of the Grafana or Prometheus UI — cluster-internal
  access (`kubectl port-forward`), same posture as ArgoCD's own UI.
- Exposing dashboards to the S3/IAM `Ingress` — Grafana gets its own
  `Service`, not routed through the existing Traefik `Ingress`; adding
  that is a clean, separable future step if ever wanted.
- Chaos test itself — separate, later sub-project (#6), for which this
  sub-project is the direct prerequisite (nothing to observe the
  chaos with, otherwise).
- Actually running this against the user's real bare-metal node — a
  separate action the user runs themselves, same framing as every
  prior sub-project.

## 4. Architecture

```
services/s3/
├── pom.xml                        # + actuator, micrometer-registry-prometheus,
│                                    #   logstash-logback-encoder
├── src/main/resources/
│   ├── application.yml             # + management.endpoints.web.exposure.include
│   └── logback-spring.xml          # new — JSON console encoder

services/iam/                       # same three changes, mirrored

deploy/helm/cloudlite/
├── Chart.yaml                      # + prometheus, loki, alloy, grafana deps
├── values.yaml                     # + top-level prometheus/loki/alloy/grafana keys
├── values-dev.yaml                 # + dev-sized PVC overrides (matches s3/postgres pattern)
├── charts/{s3,iam}/templates/
│   └── deployment.yaml             # + prometheus.io/scrape pod annotations
└── templates/grafana/
    ├── sealedsecret.yaml           # grafana-admin — same pattern as postgres/iam SealedSecrets
    ├── datasources-configmap.yaml  # Prometheus + Loki datasources, provisioned
    └── dashboards-configmap.yaml   # one dashboard, provisioned (§5)
```

No changes anywhere under `deploy/argocd/` — the existing `Application`
already syncs everything under `deploy/helm/cloudlite`, and this
sub-project only adds to that tree.

## 5. Components

### Actuator + Micrometer (`services/{s3,iam}/pom.xml`)
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```
`application.yml` addition (both services):
```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health
  endpoint:
    health:
      show-details: never
```
`show-details: never` keeps `/actuator/health` from leaking internal
state (DB connectivity detail, etc.) — it's additive to `/healthz`,
not the probe target, so no k8s manifest changes.

### JSON logging (`services/{s3,iam}/src/main/resources/logback-spring.xml`)
```xml
<dependency>
  <groupId>net.logstash.logback</groupId>
  <artifactId>logstash-logback-encoder</artifactId>
  <version>7.4</version>
</dependency>
```
```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
  </appender>
  <root level="INFO">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```
`logback-spring.xml` (not plain `logback.xml`) so Spring Boot's own
profile/property substitution still applies if ever needed later.

### `Chart.yaml` additions
```yaml
dependencies:
  # ...existing iam, s3...
  - name: prometheus
    version: "27.x"
    repository: "https://prometheus-community.github.io/helm-charts"
  - name: loki
    version: "6.x"
    repository: "https://grafana.github.io/helm-charts"
  - name: alloy
    version: "0.x"
    repository: "https://grafana.github.io/helm-charts"
  - name: grafana
    version: "8.x"
    repository: "https://grafana.github.io/helm-charts"
```
(Exact pinned versions resolved during planning against whatever's
current at implementation time — same as how `s3`/`iam` subchart
versions were pinned, not guessed here.)

### `values.yaml` — resource budget (starting point, corrected by real
`kubectl top` measurements during the plan's validation task, same
caveat as the ArgoCD sub-project's §6)

| Component | Requests | Limits |
|---|---|---|
| `prometheus` (server only — no alertmanager/pushgateway subcomponents enabled) | 150m / 256Mi | 400m / 512Mi |
| `loki` (`singleBinary`) | 100m / 192Mi | 300m / 384Mi |
| `alloy` (DaemonSet, 1 pod on this single-node cluster) | 50m / 64Mi | 150m / 128Mi |
| `grafana` | 50m / 96Mi | 150m / 192Mi |
| **New total** | **350m / 608Mi** | **1.0 vCPU / 1.2Gi** |

Roughly matches ADR 0009's ~1-1.5GB combined estimate.

### Pod scrape annotations (`charts/{s3,iam}/templates/deployment.yaml`)
```yaml
metadata:
  annotations:
    prometheus.io/scrape: "true"
    prometheus.io/path: "/actuator/prometheus"
    prometheus.io/port: "{{ .Values.service.port }}"
```
Using annotation-based discovery (Prometheus's `kubernetes_sd_configs`
+ `relabel_configs` reading these) rather than the Operator/CRD-based
`ServiceMonitor` pattern — no Prometheus Operator in this stack (that
lives in `kube-prometheus-stack`, rejected per §3), so annotations are
the correct mechanism for the plain `prometheus` chart.

### Grafana provisioning (`templates/grafana/`)
- `datasources-configmap.yaml`: a `ConfigMap` labeled
  `grafana_datasource: "1"` (the chart's default sidecar watch label)
  containing both the Prometheus and Loki datasource YAML — no manual
  "Add data source" click.
- `dashboards-configmap.yaml`: a `ConfigMap` labeled
  `grafana_dashboard: "1"` containing one dashboard's JSON model —
  panels for S3 and IAM request rate, p50/p99 latency, and error rate
  (all from Micrometer's `http.server.requests` metric), plus a Loki
  logs panel scoped to the `cloudlite` namespace. This is the concrete
  artifact §2's "validated end-to-end" goal checks against.
- `sealedsecret.yaml`: `grafana-admin`, sealed the same way as the
  existing two `SealedSecret`s during the plan's validation pass.

## 6. Capacity budget update (`docs/architecture.md` §8)

Four new rows, using §5's table:

| Pod | Language | Resource limit |
|---|---|---|
| Prometheus | — | 400m · 512Mi |
| Loki | — | 300m · 384Mi |
| Alloy (DaemonSet) | — | 150m · 128Mi |
| Grafana | — | 150m · 192Mi |

New grand total (limits, burst): **~6.4 vCPU · ~6.2Gi** — further past
the "available for pods" ~3 core / ~10Gi guidance than the ArgoCD
sub-project already left it at (~5.4 vCPU · ~5.0Gi). Per §8's existing
reasoning (limits are burst ceilings, not concurrent-usage guarantees)
this stays "fine in practice" the same way the prior overage was, but
is exactly the kind of drift §8 already flagged as worth re-measuring
under real load once Prometheus itself exists — which, after this
sub-project, it will. The plan corrects all of §5/§6's numbers with
real `kubectl top` measurements from the k3d validation pass, same as
every prior platform sub-project.

## 7. Testing

- Static: `helm template charts/prometheus`, `charts/loki`,
  `charts/alloy`, `charts/grafana` (via the umbrella chart, matching
  how `s3`/`iam` subcharts are already independently testable) to
  catch templating errors before a real cluster apply.
- Java unit level: none needed — Actuator/Micrometer/Logback
  configuration has no custom logic to unit-test; correctness is
  "does the endpoint exist and emit sane output," checked live.
- Real end-to-end validation against the k3d cluster (this sandbox's
  stand-in, per every prior platform sub-project's precedent):
  1. `mvn` build both services locally, hit `/actuator/prometheus`
     directly, confirm `http_server_requests_seconds_count` and JVM
     metrics (`jvm_memory_used_bytes`, etc.) are present.
  2. Confirm a log line printed to stdout is valid JSON with the
     expected fields.
  3. `git push` the Chart.yaml/values changes to `main` (or force an
     ArgoCD sync), confirm all four new pods reach Ready with
     `kubectl top pods` numbers sane against §6's budget.
  4. Drive real traffic through S3/IAM (bucket create, object PUT/GET,
     a deliberate 403 via IAM), then confirm in Prometheus's own UI
     that `/actuator/prometheus` targets show as `up`, and the metric
     values reflect the traffic just generated.
  5. Confirm in Grafana: both datasources auto-provisioned (no manual
     setup), the provisioned dashboard renders non-zero panels for the
     traffic from step 4, and Explore against Loki shows the
     structured log lines from that same traffic, filterable by
     `level`/`logger`.
  6. Confirm PVCs for Prometheus and Loki are `bulk-hdd`-backed
     (`kubectl get pvc -o wide`), matching `architecture.md` §4.

## 8. Open items for the implementation plan

- Exact pinned chart versions for `prometheus`/`loki`/`alloy`/`grafana`
  — resolved against whatever's current in the prometheus-community/
  grafana Helm repos at implementation time, not guessed here.
- Exact `kubectl top`-measured resource numbers for all four new
  components, to correct §5/§6's starting-budget estimates — left to
  the plan's validation task, same as every prior sub-project.
- Prometheus scrape interval and retention window (a sane default,
  e.g. 15s scrape / 15d retention, tuned if the k3d validation pass
  shows it's meaningfully wrong for this cluster's size) — left to the
  plan.
- Whether `values-dev.yaml` needs dev-sized PVC overrides for
  Prometheus/Loki (mirroring the existing `postgres.persistence.size`/
  `s3.persistence.size` pattern) — expected yes, exact sizes left to
  the plan.
- Exact dashboard JSON model (panel layout, specific PromQL/LogQL
  queries) — §5 names the panels; the plan builds and validates the
  actual JSON against real k3d traffic.
