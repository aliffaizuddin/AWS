# Prometheus + Grafana + Loki Observability (Platform Layer, Part 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give S3 and IAM real Prometheus metrics and structured logs, and
deploy Prometheus + Loki + Alloy + Grafana as new subcharts in the existing
umbrella Helm chart, proving live request/error/latency metrics and
filterable logs render in a provisioned Grafana dashboard while driving real
traffic through both services on a real k3d cluster.

**Architecture:** Micrometer/Actuator added to S3 and IAM (auto HTTP +
JVM metrics via `/actuator/prometheus`, common `application` tag, histogram
buckets for `histogram_quantile`), plus a `logback-spring.xml` JSON console
encoder on both. Four new umbrella-chart subchart dependencies
(`prometheus`, `loki`, `alloy`, `grafana`) pulled from their upstream Helm
repos and trimmed via values (no Alertmanager/node-exporter/kube-state-metrics/
pushgateway, no Loki gateway/canary, single-binary Loki, DaemonSet Alloy
tailing `/var/log/pods`). Grafana gets datasources + one dashboard
provisioned via labeled ConfigMaps (config as code, no manual clicking) and
an admin password via a new `SealedSecret`, same pattern as the two existing
ones.

**Tech Stack:** Spring Boot Actuator, Micrometer (`micrometer-registry-prometheus`),
`logstash-logback-encoder` 7.4, Helm 3, prometheus-community/prometheus
29.27.0, grafana/loki 7.3.0, grafana/alloy 1.12.1, grafana/grafana 10.5.15,
k3d (real k3s in Docker), Sealed Secrets v0.39.1 (already in this repo).

**Spec:** [`docs/superpowers/specs/2026-09-02-observability-design.md`](../specs/2026-09-02-observability-design.md)

## Global Constraints

- Chart versions are pinned exactly as resolved from each repo's live index
  at plan-writing time — do not substitute newer versions found during
  implementation without re-checking this plan's assumptions still hold:
  `prometheus-community/prometheus` `29.27.0`, `grafana/loki` `7.3.0`,
  `grafana/alloy` `1.12.1`, `grafana/grafana` `10.5.15`.
- Helm repos to add (both locally and in CI, since CI runners are
  stateless): `helm repo add prometheus-community https://prometheus-community.github.io/helm-charts`
  and `helm repo add grafana https://grafana.github.io/helm-charts`.
- **Real gotcha found during planning, not in the spec:** unlike the `s3`/
  `iam` subcharts (vendored directly as source under `charts/`), these four
  new subcharts come from remote `https://` repositories — Helm only
  resolves them via `helm dependency build`, which fetches `.tgz` archives
  into `charts/` (already `.gitignore`d, same as `Chart.lock`). Every
  `helm lint`/`helm template`/`helm install` against the umbrella chart
  from a fresh checkout — including CI — needs `helm dependency build
  deploy/helm/cloudlite` run first, after the two `helm repo add` commands
  above. `.github/workflows/ci-helm.yml` currently has no such step (it
  never needed one for the file-vendored `s3`/`iam` deps) — Task 3 fixes
  this.
- **Real gotcha found during planning, not in the spec:** S3's
  `AuthWebMvcConfigurer` intercepts every path except `/healthz` and
  `/error` and requires a valid IAM-issued JWT — without an exclusion,
  `/actuator/prometheus` would 403 on every Prometheus scrape. Task 1 adds
  `/actuator/**` to the exclusion list. IAM has no such interceptor (per
  `docs/services/iam.md`, only `/auth/token`/`/authorize` are gated), so no
  equivalent change is needed there.
- **Real gotcha found during planning, not in the spec:** `histogram_quantile`
  (used by the dashboard's latency panel) needs Micrometer to actually emit
  histogram buckets, which Spring Boot does not do by default for
  `http.server.requests` — `management.metrics.distribution.percentiles-histogram.http.server.requests: true`
  must be set explicitly, or the panel silently shows no data. Both
  services also need `management.metrics.tags.application: ${spring.application.name}`
  so one Prometheus instance's metrics are distinguishable by service — the
  dashboard's PromQL groups by this tag.
- `SealedSecret` ciphertext is bound to the sealing cluster's key
  (`docs/platform/argocd.md`'s "Known operational properties"). The k3d
  cluster from the ArgoCD sub-project no longer exists, so this plan's
  validation task creates a fresh one and re-seals **all three** secrets
  (`postgres-credentials`, `iam-jwt-secret`, and the new `grafana-admin`)
  against it — not just the new one.
- **Validation approach, a scope decision made in this plan, not the
  spec:** this sub-project validates the four new subcharts via direct
  `helm dependency build` + `helm install`/`upgrade` against a k3d cluster
  (same method the Helm chart sub-project itself used), not via a fresh
  ArgoCD bootstrap. ArgoCD's auto-pickup of ordinary subchart changes was
  already proven generically in the ArgoCD sub-project (a `git push`
  changing `values.yaml` rolled a real Deployment with zero manual
  commands); re-proving that GitOps mechanism isn't this sub-project's
  goal — proving the observability stack itself works is.
- This sandbox's Docker host is cgroup v1 — k3d cluster creation must pin
  `--image rancher/k3s:v1.28.15-k3s1` (documented in
  `docs/platform/helm-charts.md`'s known issues).
- Sealed Secrets install manifest: reuse the already-committed
  `deploy/argocd/install/sealed-secrets-install.yaml` from the ArgoCD
  sub-project — do not re-derive it.
- The k3d cluster is environment setup for Task 6 and 7 only — Tasks 1-5
  need no live cluster (Java tests run against Testcontainers Postgres;
  Helm chart edits are validated statically via `helm lint`/`helm
  template`). The cluster is created at the start of Task 6 and torn down
  at the end of Task 7.
- Repo: `https://github.com/aliffaizuddin/AWS.git`, on branch
  `docs/observability-design` (spec already committed there) — this plan's
  work continues on that branch; branch it further per your own workflow if
  preferred, but every task below assumes the spec commit is already an
  ancestor.

---

### Task 1: S3 — Actuator/Micrometer metrics + JSON logging

**Files:**
- Modify: `services/s3/pom.xml`
- Modify: `services/s3/src/main/resources/application.yml`
- Create: `services/s3/src/main/resources/logback-spring.xml`
- Modify: `services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthWebMvcConfigurer.java`
- Modify: `services/s3/src/test/java/dev/cloudlite/s3/S3ApplicationIntegrationTest.java`

**Interfaces:**
- Produces: `/actuator/prometheus` reachable without an `Authorization`
  header on port 8080, emitting metrics tagged `application="s3"` with
  histogram buckets for `http_server_requests_seconds`; stdout log lines as
  single-line JSON objects with `message`/`level`/`logger_name` fields.
  Task 3 consumes this path via pod scrape annotations; Task 4's Alloy
  config consumes the JSON log format at query time in Grafana (Task 7).

- [ ] **Step 1: Write the failing metrics-endpoint test**

Add to `services/s3/src/test/java/dev/cloudlite/s3/S3ApplicationIntegrationTest.java`
(new test method, alongside the existing `authorizeReturns403When...` tests):

```java
    @Test
    void actuatorPrometheusIsReachableWithoutAuthAndTagsMetricsByApplication() {
        restTemplate.getRestTemplate().getInterceptors().clear();

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
        assertThat(response.getBody()).contains("application=\"s3\"");
    }
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd services/s3
mvn test -Dtest=S3ApplicationIntegrationTest#actuatorPrometheusIsReachableWithoutAuthAndTagsMetricsByApplication
```
Expected: FAIL — 404 Not Found (no Actuator on the classpath yet).

- [ ] **Step 3: Add the Actuator/Micrometer dependencies**

In `services/s3/pom.xml`, inside `<dependencies>`, after the
`jackson-dataformat-xml` dependency and before the test dependencies:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
    <dependency>
      <groupId>net.logstash.logback</groupId>
      <artifactId>logstash-logback-encoder</artifactId>
      <version>7.4</version>
    </dependency>
```

- [ ] **Step 4: Add the management config**

In `services/s3/src/main/resources/application.yml`, after the `iam:`
block at the end of the file:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health
  endpoint:
    health:
      show-details: never
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

- [ ] **Step 5: Run the test again and confirm it fails differently**

```bash
mvn test -Dtest=S3ApplicationIntegrationTest#actuatorPrometheusIsReachableWithoutAuthAndTagsMetricsByApplication
```
Expected: FAIL — now 403 Forbidden, not 404. This is the auth-interceptor
gotcha from Global Constraints — the endpoint exists but `AuthWebMvcConfigurer`
is blocking it.

- [ ] **Step 6: Exclude `/actuator/**` from the auth interceptor**

In `services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthWebMvcConfigurer.java`,
change:
```java
        registry.addInterceptor(authInterceptor).excludePathPatterns("/healthz", "/error");
```
to:
```java
        registry.addInterceptor(authInterceptor).excludePathPatterns("/healthz", "/error", "/actuator/**");
```

- [ ] **Step 7: Run the test again and confirm it passes**

```bash
mvn test -Dtest=S3ApplicationIntegrationTest#actuatorPrometheusIsReachableWithoutAuthAndTagsMetricsByApplication
```
Expected: PASS.

- [ ] **Step 8: Write the failing JSON-logging test**

Add to the same test class, and add the two imports it needs
(`org.springframework.boot.test.system.CapturedOutput` and
`org.springframework.boot.test.system.OutputCaptureExtension`,
`com.fasterxml.jackson.databind.JsonNode`,
`com.fasterxml.jackson.databind.ObjectMapper`, `org.junit.jupiter.api.extension.ExtendWith`,
`org.slf4j.Logger`, `org.slf4j.LoggerFactory`) at the top of the file:

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
```

Add `@ExtendWith(OutputCaptureExtension.class)` alongside the class's
existing `@SpringBootTest`/`@Testcontainers` annotations:
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class S3ApplicationIntegrationTest {
```

Add a private static logger field and the test method:
```java
    private static final Logger log = LoggerFactory.getLogger(S3ApplicationIntegrationTest.class);

    @Test
    void logLinesAreJsonFormatted(CapturedOutput output) throws Exception {
        log.info("json-logging-smoke-test-marker");

        String jsonLine = output.getOut().lines()
            .filter(line -> line.contains("json-logging-smoke-test-marker"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("marker line not found in captured output"));

        JsonNode node = new ObjectMapper().readTree(jsonLine);
        assertThat(node.get("message").asText()).isEqualTo("json-logging-smoke-test-marker");
        assertThat(node.has("level")).isTrue();
        assertThat(node.has("logger_name")).isTrue();
    }
```

- [ ] **Step 9: Run it and confirm it fails**

```bash
mvn test -Dtest=S3ApplicationIntegrationTest#logLinesAreJsonFormatted
```
Expected: FAIL — `readTree` throws a `JsonParseException` (or similar),
since the default Logback console output is plain text, not JSON.

- [ ] **Step 10: Add `logback-spring.xml`**

Create `services/s3/src/main/resources/logback-spring.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

- [ ] **Step 11: Run it and confirm it passes**

```bash
mvn test -Dtest=S3ApplicationIntegrationTest#logLinesAreJsonFormatted
```
Expected: PASS.

- [ ] **Step 12: Run the full S3 test suite to confirm no regressions**

```bash
mvn test
```
Expected: all tests pass, including the pre-existing
`authorizeReturns403WhenNoAuthorizationHeaderIsPresent` (confirms the new
`/actuator/**` exclusion didn't loosen auth on any other path) and
`HealthControllerTest` (confirms `/healthz` still works unmodified).

- [ ] **Step 13: Commit**

```bash
cd /home/aliffaizuddin/side_project/AWS
git add services/s3/pom.xml \
        services/s3/src/main/resources/application.yml \
        services/s3/src/main/resources/logback-spring.xml \
        services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthWebMvcConfigurer.java \
        services/s3/src/test/java/dev/cloudlite/s3/S3ApplicationIntegrationTest.java
git commit -m "feat(s3): add Actuator/Micrometer metrics and JSON logging"
```

---

### Task 2: IAM — Actuator/Micrometer metrics + JSON logging

**Files:**
- Modify: `services/iam/pom.xml`
- Modify: `services/iam/src/main/resources/application.yml`
- Create: `services/iam/src/main/resources/logback-spring.xml`
- Modify: `services/iam/src/test/java/dev/cloudlite/iam/IamApplicationIntegrationTest.java`

**Interfaces:**
- Produces: `/actuator/prometheus` on port 8081 (IAM has no blanket auth
  interceptor per `docs/services/iam.md` — no exclusion needed), same
  metric tagging and JSON logging as Task 1's S3 output. Task 3 consumes
  this path via pod scrape annotations.

- [ ] **Step 1: Write the failing metrics-endpoint test**

Add to `services/iam/src/test/java/dev/cloudlite/iam/IamApplicationIntegrationTest.java`:

```java
    @Test
    void actuatorPrometheusIsReachableAndTagsMetricsByApplication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
        assertThat(response.getBody()).contains("application=\"iam\"");
    }
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd services/iam
mvn test -Dtest=IamApplicationIntegrationTest#actuatorPrometheusIsReachableAndTagsMetricsByApplication
```
Expected: FAIL — 404 Not Found.

- [ ] **Step 3: Add the Actuator/Micrometer/logging dependencies**

In `services/iam/pom.xml`, inside `<dependencies>`, after the
`jjwt-jackson` dependency and before the test dependencies:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
    <dependency>
      <groupId>net.logstash.logback</groupId>
      <artifactId>logstash-logback-encoder</artifactId>
      <version>7.4</version>
    </dependency>
```

- [ ] **Step 4: Add the management config**

In `services/iam/src/main/resources/application.yml`, after the `iam:`
block at the end of the file:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health
  endpoint:
    health:
      show-details: never
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

- [ ] **Step 5: Run the test again and confirm it passes**

```bash
mvn test -Dtest=IamApplicationIntegrationTest#actuatorPrometheusIsReachableAndTagsMetricsByApplication
```
Expected: PASS (no auth-interceptor gotcha here, unlike S3 — confirms the
Task 1 finding that IAM has no blanket interceptor).

- [ ] **Step 6: Write the failing JSON-logging test**

Add the same imports as Task 1 Step 8 to the top of
`IamApplicationIntegrationTest.java`:
```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
```

Add `@ExtendWith(OutputCaptureExtension.class)` next to the class's
existing `@SpringBootTest`/`@Testcontainers`:
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class IamApplicationIntegrationTest {
```

Add the logger field and test method:
```java
    private static final Logger log = LoggerFactory.getLogger(IamApplicationIntegrationTest.class);

    @Test
    void logLinesAreJsonFormatted(CapturedOutput output) throws Exception {
        log.info("json-logging-smoke-test-marker");

        String jsonLine = output.getOut().lines()
            .filter(line -> line.contains("json-logging-smoke-test-marker"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("marker line not found in captured output"));

        JsonNode node = new ObjectMapper().readTree(jsonLine);
        assertThat(node.get("message").asText()).isEqualTo("json-logging-smoke-test-marker");
        assertThat(node.has("level")).isTrue();
        assertThat(node.has("logger_name")).isTrue();
    }
```

- [ ] **Step 7: Run it and confirm it fails**

```bash
mvn test -Dtest=IamApplicationIntegrationTest#logLinesAreJsonFormatted
```
Expected: FAIL — plain-text output isn't valid JSON.

- [ ] **Step 8: Add `logback-spring.xml`**

Create `services/iam/src/main/resources/logback-spring.xml` — identical
content to Task 1 Step 10:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

- [ ] **Step 9: Run it and confirm it passes**

```bash
mvn test -Dtest=IamApplicationIntegrationTest#logLinesAreJsonFormatted
```
Expected: PASS.

- [ ] **Step 10: Run the full IAM test suite to confirm no regressions**

```bash
mvn test
```
Expected: all tests pass, including `HealthControllerTest` and the
existing create-user/policy/authorize end-to-end test.

- [ ] **Step 11: Commit**

```bash
cd /home/aliffaizuddin/side_project/AWS
git add services/iam/pom.xml \
        services/iam/src/main/resources/application.yml \
        services/iam/src/main/resources/logback-spring.xml \
        services/iam/src/test/java/dev/cloudlite/iam/IamApplicationIntegrationTest.java
git commit -m "feat(iam): add Actuator/Micrometer metrics and JSON logging"
```

---

### Task 3: Prometheus subchart + pod scrape annotations + CI fix

**Files:**
- Modify: `deploy/helm/cloudlite/Chart.yaml`
- Modify: `deploy/helm/cloudlite/values.yaml`
- Modify: `deploy/helm/cloudlite/values-dev.yaml`
- Modify: `deploy/helm/cloudlite/charts/s3/templates/deployment.yaml`
- Modify: `deploy/helm/cloudlite/charts/iam/templates/deployment.yaml`
- Modify: `.github/workflows/ci-helm.yml`

**Interfaces:**
- Consumes: Task 1/2's `/actuator/prometheus` on ports 8080/8081.
- Produces: a Prometheus server subchart (`cloudlite-prometheus-server`
  Service on port 80) with S3/IAM auto-discovered as scrape targets via pod
  annotations, and a working `helm dependency build` for the whole umbrella
  chart. Task 5's Grafana datasource ConfigMap consumes the service name
  `cloudlite-prometheus-server`. Task 6 consumes the dependency-build fix.

- [ ] **Step 1: Add Helm repos locally**

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update
```

- [ ] **Step 2: Add the `prometheus` dependency to `Chart.yaml`**

In `deploy/helm/cloudlite/Chart.yaml`, add to the `dependencies:` list
(after the existing `s3` entry):
```yaml
  - name: prometheus
    version: "29.27.0"
    repository: "https://prometheus-community.github.io/helm-charts"
```

- [ ] **Step 3: Add Prometheus values overrides**

In `deploy/helm/cloudlite/values.yaml`, add a new top-level `prometheus:`
key (after the existing `iam:` block):
```yaml
prometheus:
  server:
    persistentVolume:
      size: 8Gi
      storageClass: bulk-hdd
    resources:
      requests:
        cpu: 150m
        memory: 256Mi
      limits:
        cpu: 400m
        memory: 512Mi
  alertmanager:
    enabled: false
  kube-state-metrics:
    enabled: false
  prometheus-node-exporter:
    enabled: false
  prometheus-pushgateway:
    enabled: false
```

- [ ] **Step 4: Add a dev-sized PVC override**

In `deploy/helm/cloudlite/values-dev.yaml`, add (matching the existing
`postgres`/`s3` dev-size pattern):
```yaml
prometheus:
  server:
    persistentVolume:
      size: 2Gi
```

- [ ] **Step 5: Add scrape annotations to S3's pod template**

In `deploy/helm/cloudlite/charts/s3/templates/deployment.yaml`, change:
```yaml
    metadata:
      labels:
        app: s3
```
to:
```yaml
    metadata:
      labels:
        app: s3
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path: "/actuator/prometheus"
        prometheus.io/port: "8080"
```

- [ ] **Step 6: Add scrape annotations to IAM's pod template**

In `deploy/helm/cloudlite/charts/iam/templates/deployment.yaml`, change:
```yaml
    metadata:
      labels:
        app: iam
```
to:
```yaml
    metadata:
      labels:
        app: iam
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path: "/actuator/prometheus"
        prometheus.io/port: "8081"
```

- [ ] **Step 7: Fix CI to build chart dependencies before linting**

In `.github/workflows/ci-helm.yml`, add two new steps to the
`lint-and-template` job, between "Set up Helm" and "Lint umbrella chart":
```yaml
      - name: Add chart repos
        run: |
          helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
          helm repo add grafana https://grafana.github.io/helm-charts

      - name: Build umbrella chart dependencies
        run: helm dependency build deploy/helm/cloudlite
```

- [ ] **Step 8: Validate locally**

```bash
cd /home/aliffaizuddin/side_project/AWS
helm dependency build deploy/helm/cloudlite
helm lint deploy/helm/cloudlite -f deploy/helm/cloudlite/values-ci.yaml
helm template cloudlite deploy/helm/cloudlite -f deploy/helm/cloudlite/values-ci.yaml | grep -A3 "prometheus.io/scrape"
```
Expected: `helm lint` reports no errors; the `helm template` output shows
the `prometheus.io/scrape: "true"` annotation on both the `s3` and `iam`
Deployments' pod templates.

- [ ] **Step 9: Commit**

```bash
git add deploy/helm/cloudlite/Chart.yaml \
        deploy/helm/cloudlite/values.yaml \
        deploy/helm/cloudlite/values-dev.yaml \
        deploy/helm/cloudlite/charts/s3/templates/deployment.yaml \
        deploy/helm/cloudlite/charts/iam/templates/deployment.yaml \
        .github/workflows/ci-helm.yml
git commit -m "feat(helm): add trimmed Prometheus subchart and S3/IAM scrape annotations"
```

---

### Task 4: Loki + Alloy subcharts (log storage + shipping)

**Files:**
- Modify: `deploy/helm/cloudlite/Chart.yaml`
- Modify: `deploy/helm/cloudlite/values.yaml`
- Modify: `deploy/helm/cloudlite/values-dev.yaml`

**Interfaces:**
- Produces: a Loki subchart (`cloudlite-loki` Service, port 3100,
  single-binary mode) and an Alloy DaemonSet shipping every node's
  container logs to it. Task 5's Grafana datasource ConfigMap consumes the
  service name `cloudlite-loki`.

- [ ] **Step 1: Add the `loki` and `alloy` dependencies to `Chart.yaml`**

In `deploy/helm/cloudlite/Chart.yaml`, add to `dependencies:` (after the
`prometheus` entry added in Task 3):
```yaml
  - name: loki
    version: "7.3.0"
    repository: "https://grafana.github.io/helm-charts"
  - name: alloy
    version: "1.12.1"
    repository: "https://grafana.github.io/helm-charts"
```

- [ ] **Step 2: Add Loki values overrides**

In `deploy/helm/cloudlite/values.yaml`, add a new top-level `loki:` key
(after the `prometheus:` block added in Task 3):
```yaml
loki:
  deploymentMode: SingleBinary
  loki:
    auth_enabled: false
    storage:
      type: filesystem
  singleBinary:
    replicas: 1
    persistence:
      size: 8Gi
      storageClass: bulk-hdd
    resources:
      requests:
        cpu: 100m
        memory: 192Mi
      limits:
        cpu: 300m
        memory: 384Mi
  gateway:
    enabled: false
  lokiCanary:
    enabled: false
  read:
    replicas: 0
  write:
    replicas: 0
  backend:
    replicas: 0
```

- [ ] **Step 3: Add Alloy values overrides**

In `deploy/helm/cloudlite/values.yaml`, add a new top-level `alloy:` key
(after the `loki:` block just added):
```yaml
alloy:
  alloy:
    configMap:
      content: |
        discovery.kubernetes "pods" {
          role = "pod"
        }

        discovery.relabel "pods" {
          targets = discovery.kubernetes.pods.targets

          rule {
            source_labels = ["__meta_kubernetes_namespace"]
            target_label  = "namespace"
          }
          rule {
            source_labels = ["__meta_kubernetes_pod_name"]
            target_label  = "pod"
          }
          rule {
            source_labels = ["__meta_kubernetes_pod_container_name"]
            target_label  = "container"
          }
          rule {
            source_labels = ["__meta_kubernetes_pod_uid", "__meta_kubernetes_pod_container_name"]
            separator     = "/"
            target_label  = "__path__"
            replacement   = "/var/log/pods/*$1/*.log"
          }
        }

        loki.source.file "pods" {
          targets    = discovery.relabel.pods.output
          forward_to = [loki.write.default.receiver]
        }

        loki.write "default" {
          endpoint {
            url = "http://cloudlite-loki:3100/loki/api/v1/push"
          }
        }
    mounts:
      varlog: true
    resources:
      requests:
        cpu: 50m
        memory: 64Mi
      limits:
        cpu: 150m
        memory: 128Mi
```

- [ ] **Step 4: Add a dev-sized PVC override**

In `deploy/helm/cloudlite/values-dev.yaml`, add:
```yaml
loki:
  singleBinary:
    persistence:
      size: 2Gi
```

- [ ] **Step 5: Validate locally**

```bash
cd /home/aliffaizuddin/side_project/AWS
helm dependency build deploy/helm/cloudlite
helm lint deploy/helm/cloudlite -f deploy/helm/cloudlite/values-ci.yaml
helm template cloudlite deploy/helm/cloudlite -f deploy/helm/cloudlite/values-ci.yaml | grep -E "kind: (StatefulSet|DaemonSet)" 
```
Expected: `helm lint` reports no errors; the template output includes a
`StatefulSet` (Loki single-binary) and a `DaemonSet` (Alloy).

- [ ] **Step 6: Commit**

```bash
git add deploy/helm/cloudlite/Chart.yaml \
        deploy/helm/cloudlite/values.yaml \
        deploy/helm/cloudlite/values-dev.yaml
git commit -m "feat(helm): add trimmed Loki (single-binary) and Alloy subcharts"
```

---

### Task 5: Grafana subchart + provisioned datasources/dashboard

**Files:**
- Modify: `deploy/helm/cloudlite/Chart.yaml`
- Modify: `deploy/helm/cloudlite/values.yaml`
- Create: `deploy/helm/cloudlite/templates/grafana/datasources-configmap.yaml`
- Create: `deploy/helm/cloudlite/templates/grafana/dashboards-configmap.yaml`

**Interfaces:**
- Consumes: Task 3's `cloudlite-prometheus-server` Service, Task 4's
  `cloudlite-loki` Service.
- Produces: a Grafana subchart configured to reference a `grafana-admin`
  `SealedSecret` that doesn't exist yet — Task 6 creates and seals it
  against a real cluster (this task only wires the reference, same as how
  the S3/IAM Deployments already reference `postgres-credentials` before
  that secret exists in any given fresh cluster).

- [ ] **Step 1: Add the `grafana` dependency to `Chart.yaml`**

In `deploy/helm/cloudlite/Chart.yaml`, add to `dependencies:` (after the
`alloy` entry added in Task 4):
```yaml
  - name: grafana
    version: "10.5.15"
    repository: "https://grafana.github.io/helm-charts"
```

- [ ] **Step 2: Add Grafana values overrides**

In `deploy/helm/cloudlite/values.yaml`, add a new top-level `grafana:` key
(after the `alloy:` block added in Task 4):
```yaml
grafana:
  admin:
    existingSecret: grafana-admin
    userKey: admin-user
    passwordKey: admin-password
  sidecar:
    datasources:
      enabled: true
    dashboards:
      enabled: true
  persistence:
    enabled: false
  resources:
    requests:
      cpu: 50m
      memory: 96Mi
    limits:
      cpu: 150m
      memory: 192Mi
```

- [ ] **Step 3: Create the datasources ConfigMap**

Create `deploy/helm/cloudlite/templates/grafana/datasources-configmap.yaml`:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: grafana-datasources
  labels:
    grafana_datasource: "1"
data:
  datasources.yaml: |
    apiVersion: 1
    datasources:
      - name: Prometheus
        type: prometheus
        uid: prometheus
        access: proxy
        url: http://cloudlite-prometheus-server
        isDefault: true
      - name: Loki
        type: loki
        uid: loki
        access: proxy
        url: http://cloudlite-loki:3100
```

- [ ] **Step 4: Create the dashboard ConfigMap**

Create `deploy/helm/cloudlite/templates/grafana/dashboards-configmap.yaml`:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: grafana-dashboard-cloudlite
  labels:
    grafana_dashboard: "1"
data:
  cloudlite-overview.json: |
    {
      "title": "CloudLite Overview",
      "uid": "cloudlite-overview",
      "schemaVersion": 39,
      "version": 1,
      "refresh": "10s",
      "time": { "from": "now-15m", "to": "now" },
      "panels": [
        {
          "id": 1,
          "title": "Request rate (req/s)",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "targets": [
            {
              "expr": "sum by (application) (rate(http_server_requests_seconds_count[1m]))",
              "legendFormat": "{{application}}"
            }
          ]
        },
        {
          "id": 2,
          "title": "Error rate (5xx req/s)",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "targets": [
            {
              "expr": "sum by (application) (rate(http_server_requests_seconds_count{outcome=\"SERVER_ERROR\"}[1m]))",
              "legendFormat": "{{application}}"
            }
          ]
        },
        {
          "id": 3,
          "title": "Latency p99 (s)",
          "type": "timeseries",
          "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "targets": [
            {
              "expr": "histogram_quantile(0.99, sum by (le, application) (rate(http_server_requests_seconds_bucket[5m])))",
              "legendFormat": "{{application}}"
            }
          ]
        },
        {
          "id": 4,
          "title": "Logs",
          "type": "logs",
          "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
          "datasource": { "type": "loki", "uid": "loki" },
          "targets": [
            {
              "expr": "{namespace=\"cloudlite\"} | json"
            }
          ]
        }
      ]
    }
```

- [ ] **Step 5: Validate locally**

```bash
cd /home/aliffaizuddin/side_project/AWS
helm dependency build deploy/helm/cloudlite
helm lint deploy/helm/cloudlite -f deploy/helm/cloudlite/values-ci.yaml
helm template cloudlite deploy/helm/cloudlite -f deploy/helm/cloudlite/values-ci.yaml | grep -E "grafana_datasource|grafana_dashboard"
```
Expected: `helm lint` reports no errors; both labels appear in the
rendered output.

- [ ] **Step 6: Commit**

```bash
git add deploy/helm/cloudlite/Chart.yaml \
        deploy/helm/cloudlite/values.yaml \
        deploy/helm/cloudlite/templates/grafana/
git commit -m "feat(helm): add trimmed Grafana subchart with provisioned datasources and dashboard"
```

---

### Task 6: k3d cluster + sealed secrets + full-stack install

**Files:**
- Modify: `deploy/helm/cloudlite/templates/postgres/sealedsecret.yaml`
  (re-sealed against this fresh cluster's key)
- Modify: `deploy/helm/cloudlite/charts/iam/templates/sealedsecret.yaml`
  (re-sealed against this fresh cluster's key)
- Create: `deploy/helm/cloudlite/templates/grafana/sealedsecret.yaml`

**Interfaces:**
- Consumes: Tasks 1-5's complete umbrella chart.
- Produces: a running k3d cluster with the full `cloudlite` release
  installed (Postgres, S3, IAM, Prometheus, Loki, Alloy, Grafana), all pods
  Ready, plus the three `SealedSecret` files above committed to git. Task 7
  consumes this live cluster to verify actual behavior — same shell session,
  so `$GRAFANA_ADMIN_PASSWORD` (Step 4) and `$TAG` (Step 5) stay in scope.

- [ ] **Step 1: Create the k3d cluster**

```bash
k3d cluster create cloudlite-observability --image rancher/k3s:v1.28.15-k3s1 --wait
kubectl config use-context k3d-cloudlite-observability
```

- [ ] **Step 2: Install Sealed Secrets (reusing the existing manifest)**

```bash
cd /home/aliffaizuddin/side_project/AWS
kubectl create namespace sealed-secrets
kubectl apply -f deploy/argocd/install/sealed-secrets-install.yaml
kubectl rollout status deployment/sealed-secrets-controller -n sealed-secrets --timeout=120s
```
Expected: `deployment "sealed-secrets-controller" successfully rolled out`.

- [ ] **Step 3: Download kubeseal**

```bash
curl -fsSL "https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.39.1/kubeseal-0.39.1-linux-amd64.tar.gz" -o /tmp/kubeseal.tar.gz
tar -xzf /tmp/kubeseal.tar.gz -C /tmp kubeseal
chmod +x /tmp/kubeseal
```

- [ ] **Step 4: Re-seal all three secrets against this cluster**

```bash
POSTGRES_PASSWORD=$(openssl rand -base64 24)
IAM_JWT_SECRET=$(openssl rand -base64 32)
GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24)
kubectl create namespace cloudlite --dry-run=client -o yaml | kubectl apply -f -

cat > /tmp/plain-postgres-secret.yaml << EOF
apiVersion: v1
kind: Secret
metadata:
  name: postgres-credentials
  namespace: cloudlite
type: Opaque
stringData:
  POSTGRES_PASSWORD: "$POSTGRES_PASSWORD"
  SPRING_DATASOURCE_PASSWORD: "$POSTGRES_PASSWORD"
EOF
/tmp/kubeseal --format=yaml --controller-namespace=sealed-secrets \
  < /tmp/plain-postgres-secret.yaml \
  > deploy/helm/cloudlite/templates/postgres/sealedsecret.yaml

cat > /tmp/plain-iam-secret.yaml << EOF
apiVersion: v1
kind: Secret
metadata:
  name: iam-jwt-secret
  namespace: cloudlite
type: Opaque
stringData:
  IAM_JWT_SECRET: "$IAM_JWT_SECRET"
EOF
/tmp/kubeseal --format=yaml --controller-namespace=sealed-secrets \
  < /tmp/plain-iam-secret.yaml \
  > deploy/helm/cloudlite/charts/iam/templates/sealedsecret.yaml

cat > /tmp/plain-grafana-secret.yaml << EOF
apiVersion: v1
kind: Secret
metadata:
  name: grafana-admin
  namespace: cloudlite
type: Opaque
stringData:
  admin-user: "admin"
  admin-password: "$GRAFANA_ADMIN_PASSWORD"
EOF
/tmp/kubeseal --format=yaml --controller-namespace=sealed-secrets \
  < /tmp/plain-grafana-secret.yaml \
  > deploy/helm/cloudlite/templates/grafana/sealedsecret.yaml

rm -f /tmp/plain-postgres-secret.yaml /tmp/plain-iam-secret.yaml /tmp/plain-grafana-secret.yaml
echo "Recorded for Task 7 verification — do not commit these values:"
echo "GRAFANA_ADMIN_PASSWORD=$GRAFANA_ADMIN_PASSWORD"
```
Keep this shell session open through Task 7 — `$GRAFANA_ADMIN_PASSWORD` is
used there to log into Grafana.

- [ ] **Step 5: Commit the re-sealed secrets**

```bash
git add deploy/helm/cloudlite/templates/postgres/sealedsecret.yaml \
        deploy/helm/cloudlite/charts/iam/templates/sealedsecret.yaml \
        deploy/helm/cloudlite/templates/grafana/sealedsecret.yaml
git commit -m "chore(observability): re-seal secrets for the k3d validation cluster"
```
This is the same "re-seal on cluster change" operation
`docs/platform/argocd.md` already documents for `postgres-credentials`/
`iam-jwt-secret` — the ciphertext committed here is only valid for this
specific k3d cluster's key, exactly like every prior sub-project's
validation pass.

- [ ] **Step 6: Build and import the S3 and IAM images**

```bash
TAG=$(git rev-parse --short=7 HEAD)
docker build -t s3:$TAG services/s3
docker build -t iam:$TAG services/iam
k3d image import s3:$TAG iam:$TAG -c cloudlite-observability
echo "Using tag: $TAG"
```

- [ ] **Step 7: Build chart dependencies and install**

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm dependency build deploy/helm/cloudlite
helm install cloudlite deploy/helm/cloudlite -n cloudlite --create-namespace \
  -f deploy/helm/cloudlite/values-dev.yaml \
  --set s3.image.tag=$TAG \
  --set iam.image.tag=$TAG
```

- [ ] **Step 8: Wait for every pod to be Ready**

```bash
kubectl wait --for=condition=Ready pod -l app=s3 -n cloudlite --timeout=180s
kubectl wait --for=condition=Ready pod -l app=iam -n cloudlite --timeout=180s
kubectl wait --for=condition=Ready pod -l app.kubernetes.io/name=prometheus -n cloudlite --timeout=180s
kubectl wait --for=condition=Ready pod -l app.kubernetes.io/name=loki -n cloudlite --timeout=180s
kubectl wait --for=condition=Ready pod -l app.kubernetes.io/name=alloy -n cloudlite --timeout=180s
kubectl wait --for=condition=Ready pod -l app.kubernetes.io/name=grafana -n cloudlite --timeout=180s
kubectl get pods -n cloudlite
```
Expected: every pod listed as `Running`/`1/1` or `2/2` Ready (Grafana's pod
runs an extra sidecar container, so `2/2` there is correct, not a
failure).

---

### Task 7: Live verification + capacity measurement + teardown

**Files:** none.

**Interfaces:**
- Consumes: Task 6's live cluster and `$TAG`/`$GRAFANA_ADMIN_PASSWORD`
  shell variables (same session).
- Produces: confirmation that metrics, logs, and the provisioned dashboard
  all show real data, plus real `kubectl top` measurements for Task 8.

- [ ] **Step 1: Drive real traffic through S3 and IAM**

Per `services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthInterceptor.java`,
S3 maps `PUT /{bucket}` to the `s3:CreateBucket` action and
`PUT/GET/DELETE /{bucket}/{key}` to `s3:PutObject`/`s3:GetObject`/
`s3:DeleteObject`, each checked against a resource ARN
(`arn:cloudlite:s3:::{bucket}` or `.../{bucket}/{key}`) — the policy below
grants exactly those, scoped to one test bucket:

```bash
kubectl port-forward svc/s3 -n cloudlite 8080:8080 &
S3_PID=$!
kubectl port-forward svc/iam -n cloudlite 8081:8081 &
IAM_PID=$!
sleep 3

CREATED_USER=$(curl -s -X POST http://localhost:8081/users -H "Content-Type: application/json" -d '{"username":"obs-smoke-test"}')
USER_ID=$(echo "$CREATED_USER" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
APIKEY=$(echo "$CREATED_USER" | python3 -c 'import sys,json; print(json.load(sys.stdin)["apiKey"])')

POLICY=$(curl -s -X POST http://localhost:8081/policies -H "Content-Type: application/json" -d '{
  "name": "obs-smoke-test-policy",
  "document": {
    "statements": [
      {
        "effect": "ALLOW",
        "actions": ["s3:CreateBucket", "s3:ListBucket", "s3:DeleteBucket", "s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
        "resources": ["arn:cloudlite:s3:::obs-smoke-bucket", "arn:cloudlite:s3:::obs-smoke-bucket/*"]
      }
    ]
  }
}')
POLICY_ID=$(echo "$POLICY" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
curl -s -X POST "http://localhost:8081/users/$USER_ID/policies/$POLICY_ID"

TOKEN=$(curl -s -X POST http://localhost:8081/auth/token -H "Authorization: ApiKey $APIKEY" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')
AUTH="Authorization: Bearer $TOKEN"

for i in $(seq 1 20); do
  curl -s -o /dev/null -X PUT http://localhost:8080/obs-smoke-bucket -H "$AUTH"
  curl -s -o /dev/null -X PUT http://localhost:8080/obs-smoke-bucket/file.txt -H "$AUTH" -H "Content-Type: text/plain" -d "hello $i"
  curl -s -o /dev/null -X GET http://localhost:8080/obs-smoke-bucket/file.txt -H "$AUTH"
  curl -s -o /dev/null -X GET http://localhost:8080/obs-smoke-bucket/missing-$i.txt -H "$AUTH"
done

kill $S3_PID $IAM_PID
```
This generates a real mix of 2xx responses (bucket/object create, get) and
one real S3-level 404 per loop (`GET .../missing-$i.txt`, a well-formed,
IAM-authorized request for an object that doesn't exist) — enough for the
dashboard's rate and latency panels to show non-zero data. The "Error rate
(5xx)" panel legitimately staying near zero here is correct, not a defect
— this smoke test doesn't manufacture server errors, and 404s aren't 5xx.

- [ ] **Step 2: Confirm Prometheus is scraping both services**

```bash
kubectl port-forward svc/cloudlite-prometheus-server -n cloudlite 9090:80 &
PROM_PID=$!
sleep 3
curl -s "http://localhost:9090/api/v1/query?query=up" | python3 -m json.tool
kill $PROM_PID
```
Expected: the result includes entries with `"job":"kubernetes-pods"` and
`"application":"s3"` / `"application":"iam"`, both with value `1` (up).

- [ ] **Step 3: Confirm Grafana's dashboard renders real data**

```bash
kubectl port-forward svc/cloudlite-grafana -n cloudlite 3000:80 &
GRAF_PID=$!
sleep 3
curl -s -u "admin:$GRAFANA_ADMIN_PASSWORD" http://localhost:3000/api/dashboards/uid/cloudlite-overview | python3 -m json.tool | head -20
curl -s -u "admin:$GRAFANA_ADMIN_PASSWORD" "http://localhost:3000/api/datasources" | python3 -m json.tool
kill $GRAF_PID
```
Expected: the dashboard lookup returns the "CloudLite Overview" dashboard
(confirms the sidecar picked up the ConfigMap); the datasources list shows
both `Prometheus` and `Loki`, `access: proxy` — this is the concrete proof
that provisioning worked with zero manual "Add data source" clicks.

- [ ] **Step 4: Confirm Loki has real, JSON-structured log lines**

```bash
kubectl port-forward svc/cloudlite-loki -n cloudlite 3100:3100 &
LOKI_PID=$!
sleep 3
curl -s -G "http://localhost:3100/loki/api/v1/query_range" \
  --data-urlencode 'query={namespace="cloudlite", app="s3"}' \
  --data-urlencode 'limit=5' | python3 -m json.tool
kill $LOKI_PID
```
Expected: at least one log line returned, and its content is a JSON object
(confirms Alloy shipped Task 1's JSON-encoded log output, not
plain text).

- [ ] **Step 5: Confirm PVCs are `bulk-hdd`-backed**

```bash
kubectl get pvc -n cloudlite -o custom-columns=NAME:.metadata.name,STORAGECLASS:.spec.storageClassName
```
Expected: the Prometheus and Loki PVCs both show `bulk-hdd`, matching
`architecture.md` §4.

- [ ] **Step 6: Capture real resource usage for Task 8**

```bash
kubectl top pods -n cloudlite 2>&1 || echo "METRICS_SERVER_UNAVAILABLE"
```
If real numbers print, record them verbatim in this task's report for
Task 8. If `METRICS_SERVER_UNAVAILABLE` prints instead, record that — Task
8 falls back to the configured `requests`/`limits` values from Tasks 3-5
(already known) rather than live-measured numbers, same fallback the
ArgoCD sub-project used.

- [ ] **Step 7: Tear down**

```bash
helm uninstall cloudlite -n cloudlite
k3d cluster delete cloudlite-observability
```

---

### Task 8: Capacity budget doc update + platform doc

**Files:**
- Modify: `docs/architecture.md` (§8's capacity budget table)
- Create: `docs/platform/observability.md`

**Interfaces:**
- Consumes: Task 7's report (real `kubectl top` numbers, or the
  configured-values fallback; confirmation that metrics/logs/dashboard
  verification all passed).

- [ ] **Step 1: Update the capacity budget table**

In `docs/architecture.md` §8, replace the single placeholder row:
```
| Monitoring (Prometheus+Grafana) | — | 0.75 vCPU · 768Mi |
```
with four real rows (immediately before the `Web UI` row):
```
| Prometheus | — | 400m · 512Mi |
| Loki | — | 300m · 384Mi |
| Alloy (DaemonSet) | — | 150m · 128Mi |
| Grafana | — | 150m · 192Mi |
```
If Task 7's report has real `kubectl top` numbers instead of the
`METRICS_SERVER_UNAVAILABLE` fallback, use those real numbers for each row
in place of the configured limits shown above.

Update the `**Total (limits, burst)**` row: remove the old placeholder's
0.75 vCPU / 768Mi from the existing ~5.4 vCPU / ~5.0Gi total, then add the
four new rows' 1.0 vCPU / 1.2Gi (400m+300m+150m+150m = 1000m; 512+384+128+192=1216Mi ≈ 1.2Gi):
```
| **Total (limits, burst)** | | **~5.65 vCPU · ~5.6Gi** |
```
Update the surrounding prose paragraph's "Worth re-measuring under real
load once observability (Prometheus) lands" sentence — Prometheus now
exists, so reword it to note this table's Prometheus/Loki/Alloy/Grafana
rows come from Task 7's real k3d measurements (or the documented
configured-values fallback), not aspirational estimates.

- [ ] **Step 2: Write the platform doc**

Create `docs/platform/observability.md`:
```markdown
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
dashboard render with zero manual configuration; Loki holds real,
JSON-structured log lines shipped by Alloy; Prometheus's and Loki's PVCs
are `bulk-hdd`-backed.

## Known operational properties (not defects)

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

## Out of scope

Custom application metrics (e.g. an IAM allow/deny counter) — deferred,
auto-instrumentation was sufficient for this sub-project. Alerting/
Alertmanager — no on-call story for a single-operator project.
`node-exporter`/`kube-state-metrics` — cluster/node-level metrics aren't
this sub-project's story. Long-term retention tuning. TLS/ingress exposure
of Grafana or Prometheus. Chaos test (separate, later sub-project — this
sub-project is its direct prerequisite). Actually running this against the
user's real bare-metal node.
```

- [ ] **Step 3: Commit**

```bash
git add docs/architecture.md docs/platform/observability.md
git commit -m "docs: add observability platform doc and update the capacity budget"
```
