# Helm Charts (Platform Layer, Part 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create `deploy/helm/cloudlite/` — an umbrella Helm chart packaging `services/s3` and `services/iam` as subcharts plus a hand-rolled Postgres StatefulSet, `fast-ssd`/`bulk-hdd` StorageClasses, and a Traefik-routed Ingress — validated end-to-end against a local k3d cluster.

**Architecture:** One umbrella chart at `deploy/helm/cloudlite/` with `charts/s3/` and `charts/iam/` as independently-testable subcharts (per ADR 0006), plus umbrella-level templates for the namespace, StorageClasses, Ingress, and Postgres (not a subchart — it isn't one of this project's own services). A `global.namespace` value is shared across the parent and every subchart via Helm's `global` values mechanism.

**Tech Stack:** Helm 3, Kubernetes manifests (apps/v1, v1, networking.k8s.io/v1, storage.k8s.io/v1), k3d (real k3s in Docker) for sandbox validation.

**Spec:** [`docs/superpowers/specs/2026-08-22-helm-charts-design.md`](../specs/2026-08-22-helm-charts-design.md)

## Global Constraints

- Chart root: `deploy/helm/cloudlite/` — a fresh Helm chart; nothing under `deploy/` exists yet on any branch.
- Single namespace `cloudlite` for every resource, shared via `global.namespace` in values (NOT a plain top-level `namespace:` key — Helm's `global.*` values are the only ones automatically visible inside subcharts; a plain top-level key in the parent's `values.yaml` is only visible to the parent's own umbrella-level templates).
- Postgres is a hand-rolled `StatefulSet` at the umbrella level, NOT a subchart — `architecture.md` §9's repo structure only lists `{s3, iam, fnrunner, web}` as the umbrella chart's own subcharts.
- Resource limits/requests match `architecture.md` §8 exactly: S3 `1 vCPU/1Gi` limit, IAM `0.75 vCPU/768Mi` limit, Postgres `1 vCPU/1Gi` limit; requests are half of limits per §8's own convention.
- Both app images use `imagePullPolicy: IfNotPresent` (locally-built-and-imported images for sandbox validation, never pulled from a registry in this phase).
- Secrets (`postgres-credentials`, `iam-jwt-secret`) are real Kubernetes `Secret` objects referenced via `envFrom.secretRef` — no secret value is ever written into any `values.yaml` or `ConfigMap`. They are plain/unsealed for this phase (Sealed Secrets is a separate, later sub-project).
- **Deliberate simplification vs. the spec's aspirational wording:** the spec's §5 describes `fast-ssd`/`bulk-hdd` as "distinguished via `local-path-provisioner`'s per-class path configuration." In practice, k3s's single bundled `local-path-provisioner` instance is bound to one provisioner name and one node-path config — it does not natively support two `StorageClass`es with genuinely different backing directories without deploying a second, independently-configured provisioner instance (extra Deployment/RBAC/ConfigMap). That's real, non-trivial additional infrastructure whose only payoff is a sandbox-only cosmetic distinction — the REAL SSD/HDD physical separation only means anything once this chart is applied to the user's actual dual-disk hardware, which is a separate, later sub-project this session can't reach anyway. This plan therefore defines both `StorageClass`es using the SAME `provisioner: rancher.io/local-path` (both physically land in the same host directory in this sandbox) — the goal here is proving the app-level contract (two independently-named, independently-referenceable `StorageClass`es that each service's PVC correctly binds to), not physical disk separation. Achieving genuine physical separation is called out as an open item for whoever applies this chart to real hardware.
- Every task commits with a Conventional Commit message (`feat|test|build|docs`) per `docs/decisions/0012-commit-and-branch-conventions.md`.

---

## Task 1: Umbrella chart scaffolding

**Files:**
- Create: `deploy/helm/cloudlite/Chart.yaml`
- Create: `deploy/helm/cloudlite/values.yaml`
- Create: `deploy/helm/cloudlite/values-dev.yaml`
- Create: `deploy/helm/cloudlite/templates/namespace.yaml`

**Interfaces:**
- Produces: `global.namespace` (value `cloudlite`), readable via `{{ .Values.global.namespace }}` from every umbrella-level template AND from every subchart's own templates (Helm's `global` values are automatically shared with all subcharts — this is the mechanism every later task's templates rely on for their `metadata.namespace`).
- Consumes: nothing from earlier tasks — this is the first task.

- [ ] **Step 1: Create `Chart.yaml`**

```yaml
apiVersion: v2
name: cloudlite
description: CloudLite platform — S3 + IAM clone, self-hosted on k3s
type: application
version: 0.1.0
appVersion: "0.1.0"
```

- [ ] **Step 2: Create `values.yaml`**

```yaml
global:
  namespace: cloudlite
```

- [ ] **Step 3: Create `values-dev.yaml`**

```yaml
# Local/dev overrides layered over values.yaml — never fork the chart
# per environment, per ADR 0006. Empty for now; later tasks add
# smaller persistence sizes here.
```

- [ ] **Step 4: Create the namespace template**

`deploy/helm/cloudlite/templates/namespace.yaml`:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: {{ .Values.global.namespace }}
```

- [ ] **Step 5: Verify the chart lints and renders**

Run: `cd deploy/helm/cloudlite && helm lint .`
Expected: `0 chart(s) failed`

Run: `helm template cloudlite . | grep -A2 "kind: Namespace"`
Expected: shows `metadata: name: cloudlite`

- [ ] **Step 6: Commit**

```bash
git add deploy/helm/cloudlite/Chart.yaml deploy/helm/cloudlite/values.yaml \
  deploy/helm/cloudlite/values-dev.yaml deploy/helm/cloudlite/templates/namespace.yaml
git commit -m "feat: scaffold the cloudlite umbrella Helm chart"
```

---

## Task 2: StorageClasses

**Files:**
- Create: `deploy/helm/cloudlite/templates/storageclasses.yaml`

**Interfaces:**
- Produces: two cluster-scoped `StorageClass` resources named exactly `fast-ssd` and `bulk-hdd` — later tasks' `PersistentVolumeClaim`/`volumeClaimTemplates` reference these names in their `storageClassName` field.
- Consumes: nothing from earlier tasks (this template has no `metadata.namespace` — `StorageClass` is cluster-scoped, not namespaced).

- [ ] **Step 1: Create the StorageClasses template**

`deploy/helm/cloudlite/templates/storageclasses.yaml`:

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: fast-ssd
provisioner: rancher.io/local-path
volumeBindingMode: WaitForFirstConsumer
reclaimPolicy: Delete
---
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: bulk-hdd
provisioner: rancher.io/local-path
volumeBindingMode: WaitForFirstConsumer
reclaimPolicy: Delete
```

(`reclaimPolicy: Delete` is hardcoded, not values-driven — per the spec's Open Items, `Delete` is the right choice for a sandbox-validation cluster that gets torn down repeatedly; revisit for the real bare-metal deployment. See this plan's Global Constraints for why both classes share one provisioner in this phase.)

- [ ] **Step 2: Verify the chart still lints and renders**

Run: `cd deploy/helm/cloudlite && helm lint . && helm template cloudlite . | grep "kind: StorageClass" -A2`
Expected: `0 chart(s) failed`; output shows both `fast-ssd` and `bulk-hdd`.

- [ ] **Step 3: Commit**

```bash
git add deploy/helm/cloudlite/templates/storageclasses.yaml
git commit -m "feat: add fast-ssd and bulk-hdd StorageClasses"
```

---

## Task 3: Postgres (StatefulSet + Service + ConfigMap + Secret)

**Files:**
- Create: `deploy/helm/cloudlite/templates/postgres/configmap.yaml`
- Create: `deploy/helm/cloudlite/templates/postgres/secret.yaml`
- Create: `deploy/helm/cloudlite/templates/postgres/service.yaml`
- Create: `deploy/helm/cloudlite/templates/postgres/statefulset.yaml`
- Modify: `deploy/helm/cloudlite/values.yaml`
- Modify: `deploy/helm/cloudlite/values-dev.yaml`

**Interfaces:**
- Produces: a `Service` named `postgres` reachable in-cluster at `postgres:5432` (Tasks 4 and 5 both use this exact DNS name in their `SPRING_DATASOURCE_URL`). A `Secret` named `postgres-credentials` with TWO keys holding the same password value: `POSTGRES_PASSWORD` (consumed by this task's own `StatefulSet`) and `SPRING_DATASOURCE_PASSWORD` (consumed by Tasks 4 and 5's Spring Boot containers via `envFrom.secretRef` — both key names must exist on this one Secret so a single `envFrom` entry satisfies both the Postgres container's own bootstrap env var and every Spring Boot container's expected env var name, with zero per-key `secretKeyRef` mapping needed anywhere).
- Consumes: `global.namespace` (Task 1); `fast-ssd` `StorageClass` (Task 2).

- [ ] **Step 1: Create the ConfigMap**

`deploy/helm/cloudlite/templates/postgres/configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: postgres-config
  namespace: {{ .Values.global.namespace }}
data:
  POSTGRES_DB: cloudlite
  POSTGRES_USER: cloudlite
  PGDATA: /var/lib/postgresql/data/pgdata
```

(`PGDATA` is set to a subdirectory of the mount point, not the mount point itself — mounting a PVC directly at Postgres's data directory can fail `initdb` with "directory not empty" on some volume/filesystem combinations that pre-populate a `lost+found` entry; a subdirectory sidesteps this entirely, a well-known operational gotcha for Postgres-on-Kubernetes.)

- [ ] **Step 2: Create the Secret**

`deploy/helm/cloudlite/templates/postgres/secret.yaml`:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: postgres-credentials
  namespace: {{ .Values.global.namespace }}
type: Opaque
stringData:
  POSTGRES_PASSWORD: {{ .Values.postgres.password | quote }}
  SPRING_DATASOURCE_PASSWORD: {{ .Values.postgres.password | quote }}
```

- [ ] **Step 3: Create the Service**

`deploy/helm/cloudlite/templates/postgres/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: {{ .Values.global.namespace }}
spec:
  selector:
    app: postgres
  ports:
    - port: 5432
      targetPort: 5432
```

- [ ] **Step 4: Create the StatefulSet**

`deploy/helm/cloudlite/templates/postgres/statefulset.yaml`:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres
  namespace: {{ .Values.global.namespace }}
spec:
  serviceName: postgres
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
        - name: postgres
          image: "{{ .Values.postgres.image.repository }}:{{ .Values.postgres.image.tag }}"
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 5432
          envFrom:
            - configMapRef:
                name: postgres-config
            - secretRef:
                name: postgres-credentials
          volumeMounts:
            - name: postgres-data
              mountPath: /var/lib/postgresql/data
          resources:
            limits:
              cpu: {{ .Values.postgres.resources.limits.cpu | quote }}
              memory: {{ .Values.postgres.resources.limits.memory | quote }}
            requests:
              cpu: {{ .Values.postgres.resources.requests.cpu | quote }}
              memory: {{ .Values.postgres.resources.requests.memory | quote }}
  volumeClaimTemplates:
    - metadata:
        name: postgres-data
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: {{ .Values.postgres.persistence.storageClassName }}
        resources:
          requests:
            storage: {{ .Values.postgres.persistence.size }}
```

(Note: `envFrom` on the Postgres container itself picks up `POSTGRES_PASSWORD` from the Secret — it also picks up `SPRING_DATASOURCE_PASSWORD` as a harmless, unused extra env var inside the Postgres container. This is a deliberate, acceptable trade-off for the "one Secret, two key names" simplification described in this task's Interfaces block, not an oversight.)

- [ ] **Step 5: Add the `postgres` block to `values.yaml`**

The full file becomes:

```yaml
global:
  namespace: cloudlite

postgres:
  image:
    repository: postgres
    tag: "16-alpine"
  password: cloudlite-dev-only-password
  resources:
    limits:
      cpu: "1"
      memory: 1Gi
    requests:
      cpu: 500m
      memory: 512Mi
  persistence:
    size: 10Gi
    storageClassName: fast-ssd
```

- [ ] **Step 6: Add a smaller persistence override to `values-dev.yaml`**

The full file becomes:

```yaml
# Local/dev overrides layered over values.yaml — never fork the chart
# per environment, per ADR 0006.
postgres:
  persistence:
    size: 2Gi
```

- [ ] **Step 7: Verify the chart still lints and renders**

Run: `cd deploy/helm/cloudlite && helm lint . -f values-dev.yaml && helm template cloudlite . -f values-dev.yaml | grep -E "kind: (StatefulSet|Service|Secret|ConfigMap)"`
Expected: `0 chart(s) failed`; output shows all four Postgres resources.

- [ ] **Step 8: Commit**

```bash
git add deploy/helm/cloudlite/templates/postgres deploy/helm/cloudlite/values.yaml deploy/helm/cloudlite/values-dev.yaml
git commit -m "feat: add Postgres StatefulSet, Service, ConfigMap, and Secret"
```

---

## Task 4: `charts/iam` subchart

**Files:**
- Create: `deploy/helm/cloudlite/charts/iam/Chart.yaml`
- Create: `deploy/helm/cloudlite/charts/iam/values.yaml`
- Create: `deploy/helm/cloudlite/charts/iam/templates/secret.yaml`
- Create: `deploy/helm/cloudlite/charts/iam/templates/deployment.yaml`
- Create: `deploy/helm/cloudlite/charts/iam/templates/service.yaml`

**Interfaces:**
- Produces: a `Service` named `iam` reachable in-cluster at `iam:8081` (Task 5 uses this exact DNS name for `IAM_BASE_URL`).
- Consumes: `global.namespace` (Task 1, automatically visible here via Helm's `global` values mechanism — no explicit passing needed); the `postgres-credentials` Secret's `SPRING_DATASOURCE_PASSWORD` key and the `postgres` Service's `postgres:5432` DNS name (both from Task 3).

Note: this subchart's own `values.yaml` below is a complete, standalone set of defaults — `helm template charts/iam` renders correctly on its own (per ADR 0006's "independently testable" requirement), and when installed as part of the umbrella chart, Helm automatically shares `global.namespace` down from the parent's `values.yaml` with no extra wiring.

- [ ] **Step 1: Create `Chart.yaml`**

`deploy/helm/cloudlite/charts/iam/Chart.yaml`:

```yaml
apiVersion: v2
name: iam
description: CloudLite IAM subchart
type: application
version: 0.1.0
appVersion: "0.1.0"
```

- [ ] **Step 2: Create `values.yaml`**

`deploy/helm/cloudlite/charts/iam/values.yaml`:

```yaml
global:
  namespace: cloudlite

replicaCount: 1

image:
  repository: iam
  tag: "0.1.0"

resources:
  limits:
    cpu: "750m"
    memory: 768Mi
  requests:
    cpu: 375m
    memory: 384Mi

jwt:
  secret: dev-only-insecure-jwt-signing-secret-please-change
  expirySeconds: 900

datasource:
  url: "jdbc:postgresql://postgres:5432/cloudlite"
  username: cloudlite
```

(`global.namespace` is repeated here as this subchart's own standalone default — when installed as part of the umbrella chart, the parent's `global.namespace` value takes precedence for every chart, including this one; this default only matters for standalone `helm template charts/iam` testing.)

- [ ] **Step 3: Create the JWT Secret**

`deploy/helm/cloudlite/charts/iam/templates/secret.yaml`:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: iam-jwt-secret
  namespace: {{ .Values.global.namespace }}
type: Opaque
stringData:
  IAM_JWT_SECRET: {{ .Values.jwt.secret | quote }}
```

- [ ] **Step 4: Create the Deployment**

`deploy/helm/cloudlite/charts/iam/templates/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: iam
  namespace: {{ .Values.global.namespace }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: iam
  template:
    metadata:
      labels:
        app: iam
    spec:
      containers:
        - name: iam
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8081
          env:
            - name: SERVER_PORT
              value: "8081"
            - name: SPRING_DATASOURCE_URL
              value: {{ .Values.datasource.url | quote }}
            - name: SPRING_DATASOURCE_USERNAME
              value: {{ .Values.datasource.username | quote }}
            - name: IAM_JWT_EXPIRY_SECONDS
              value: {{ .Values.jwt.expirySeconds | quote }}
            - name: JAVA_TOOL_OPTIONS
              value: "-Xmx512m"
          envFrom:
            - secretRef:
                name: postgres-credentials
            - secretRef:
                name: iam-jwt-secret
          readinessProbe:
            httpGet:
              path: /healthz
              port: 8081
            initialDelaySeconds: 10
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /healthz
              port: 8081
            initialDelaySeconds: 10
            periodSeconds: 10
          resources:
            limits:
              cpu: {{ .Values.resources.limits.cpu | quote }}
              memory: {{ .Values.resources.limits.memory | quote }}
            requests:
              cpu: {{ .Values.resources.requests.cpu | quote }}
              memory: {{ .Values.resources.requests.memory | quote }}
```

- [ ] **Step 5: Create the Service**

`deploy/helm/cloudlite/charts/iam/templates/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: iam
  namespace: {{ .Values.global.namespace }}
spec:
  selector:
    app: iam
  ports:
    - port: 8081
      targetPort: 8081
```

- [ ] **Step 6: Verify the subchart renders standalone AND as part of the umbrella chart**

Run: `cd deploy/helm/cloudlite/charts/iam && helm lint . && helm template iam .`
Expected: `0 chart(s) failed`; renders a Secret, Deployment, and Service.

Run: `cd ../.. && helm lint . -f values-dev.yaml && helm template cloudlite . -f values-dev.yaml | grep -c "name: iam"`
Expected: `0 chart(s) failed`; the grep count is at least 3 (Secret, Deployment, Service all named `iam`/`iam-jwt-secret`), confirming the umbrella chart's render includes the iam subchart's resources.

- [ ] **Step 7: Commit**

```bash
git add deploy/helm/cloudlite/charts/iam
git commit -m "feat: add charts/iam subchart"
```

---

## Task 5: `charts/s3` subchart

**Files:**
- Create: `deploy/helm/cloudlite/charts/s3/Chart.yaml`
- Create: `deploy/helm/cloudlite/charts/s3/values.yaml`
- Create: `deploy/helm/cloudlite/charts/s3/templates/deployment.yaml`
- Create: `deploy/helm/cloudlite/charts/s3/templates/service.yaml`
- Create: `deploy/helm/cloudlite/charts/s3/templates/pvc.yaml`
- Modify: `deploy/helm/cloudlite/values-dev.yaml`

**Interfaces:**
- Produces: a `Service` named `s3` reachable in-cluster at `s3:8080` (Task 6's Ingress routes to this). A `PersistentVolumeClaim` named `s3-data` bound to the `bulk-hdd` `StorageClass`.
- Consumes: `global.namespace` (Task 1); the `postgres-credentials` Secret's `SPRING_DATASOURCE_PASSWORD` key and the `postgres` Service's `postgres:5432` DNS name (Task 3); the `iam` Service's `iam:8081` DNS name (Task 4); the `bulk-hdd` `StorageClass` (Task 2).

- [ ] **Step 1: Create `Chart.yaml`**

`deploy/helm/cloudlite/charts/s3/Chart.yaml`:

```yaml
apiVersion: v2
name: s3
description: CloudLite S3 subchart
type: application
version: 0.1.0
appVersion: "0.1.0"
```

- [ ] **Step 2: Create `values.yaml`**

`deploy/helm/cloudlite/charts/s3/values.yaml`:

```yaml
global:
  namespace: cloudlite

replicaCount: 1

image:
  repository: s3
  tag: "0.1.0"

resources:
  limits:
    cpu: "1"
    memory: 1Gi
  requests:
    cpu: 500m
    memory: 512Mi

persistence:
  size: 20Gi
  storageClassName: bulk-hdd

datasource:
  url: "jdbc:postgresql://postgres:5432/cloudlite"
  username: cloudlite

iam:
  baseUrl: "http://iam:8081"
```

- [ ] **Step 3: Create the PersistentVolumeClaim**

`deploy/helm/cloudlite/charts/s3/templates/pvc.yaml`:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: s3-data
  namespace: {{ .Values.global.namespace }}
spec:
  accessModes: ["ReadWriteOnce"]
  storageClassName: {{ .Values.persistence.storageClassName }}
  resources:
    requests:
      storage: {{ .Values.persistence.size }}
```

- [ ] **Step 4: Create the Deployment**

`deploy/helm/cloudlite/charts/s3/templates/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: s3
  namespace: {{ .Values.global.namespace }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: s3
  template:
    metadata:
      labels:
        app: s3
    spec:
      containers:
        - name: s3
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
          env:
            - name: SERVER_PORT
              value: "8080"
            - name: S3_DATA_DIR
              value: "/data"
            - name: SPRING_DATASOURCE_URL
              value: {{ .Values.datasource.url | quote }}
            - name: SPRING_DATASOURCE_USERNAME
              value: {{ .Values.datasource.username | quote }}
            - name: IAM_BASE_URL
              value: {{ .Values.iam.baseUrl | quote }}
            - name: JAVA_TOOL_OPTIONS
              value: "-Xmx768m"
          envFrom:
            - secretRef:
                name: postgres-credentials
          volumeMounts:
            - name: s3-data
              mountPath: /data
          readinessProbe:
            httpGet:
              path: /healthz
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /healthz
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 10
          resources:
            limits:
              cpu: {{ .Values.resources.limits.cpu | quote }}
              memory: {{ .Values.resources.limits.memory | quote }}
            requests:
              cpu: {{ .Values.resources.requests.cpu | quote }}
              memory: {{ .Values.resources.requests.memory | quote }}
      volumes:
        - name: s3-data
          persistentVolumeClaim:
            claimName: s3-data
```

- [ ] **Step 5: Create the Service**

`deploy/helm/cloudlite/charts/s3/templates/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: s3
  namespace: {{ .Values.global.namespace }}
spec:
  selector:
    app: s3
  ports:
    - port: 8080
      targetPort: 8080
```

- [ ] **Step 6: Add a smaller persistence override to `values-dev.yaml`**

The full file becomes:

```yaml
# Local/dev overrides layered over values.yaml — never fork the chart
# per environment, per ADR 0006.
postgres:
  persistence:
    size: 2Gi
s3:
  persistence:
    size: 2Gi
```

- [ ] **Step 7: Verify the subchart renders standalone AND as part of the umbrella chart**

Run: `cd deploy/helm/cloudlite/charts/s3 && helm lint . && helm template s3 .`
Expected: `0 chart(s) failed`; renders a PVC, Deployment, and Service.

Run: `cd ../.. && helm lint . -f values-dev.yaml && helm template cloudlite . -f values-dev.yaml | grep -c "name: s3"`
Expected: `0 chart(s) failed`; grep count is at least 3 (PVC, Deployment, Service all named `s3`/`s3-data`).

- [ ] **Step 8: Commit**

```bash
git add deploy/helm/cloudlite/charts/s3 deploy/helm/cloudlite/values-dev.yaml
git commit -m "feat: add charts/s3 subchart"
```

---

## Task 6: Ingress

**Files:**
- Create: `deploy/helm/cloudlite/templates/ingress.yaml`

**Interfaces:**
- Consumes: `global.namespace` (Task 1); the `s3` Service on port 8080 (Task 5) and the `iam` Service on port 8081 (Task 4).
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Create the Ingress**

`deploy/helm/cloudlite/templates/ingress.yaml`:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: cloudlite
  namespace: {{ .Values.global.namespace }}
spec:
  ingressClassName: traefik
  rules:
    - host: s3.cloudlite.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: s3
                port:
                  number: 8080
    - host: iam.cloudlite.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: iam
                port:
                  number: 8081
```

- [ ] **Step 2: Verify the chart still lints and renders**

Run: `cd deploy/helm/cloudlite && helm lint . -f values-dev.yaml && helm template cloudlite . -f values-dev.yaml | grep -A20 "kind: Ingress"`
Expected: `0 chart(s) failed`; output shows both host rules.

- [ ] **Step 3: Commit**

```bash
git add deploy/helm/cloudlite/templates/ingress.yaml
git commit -m "feat: add Traefik-routed ingress for s3 and iam"
```

---

## Task 7: End-to-end validation against a real k3d cluster + platform doc

**Files:**
- Create: `docs/platform/helm-charts.md`

**Interfaces:**
- Consumes: the full chart from Tasks 1-6.
- Produces: nothing later tasks depend on — this is the last task in the plan.

This task has no new chart YAML — it proves the chart from Tasks 1-6 actually works against a real (if disposable) Kubernetes cluster, and documents what was built. `helm lint`/`helm template` (already run after every prior task) only prove the YAML is well-formed; they don't prove pods actually start, PVCs actually bind, or the ingress actually routes.

- [ ] **Step 1: Install `helm` and `k3d` if not already present**

Run: `command -v helm || curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash`
Run: `command -v k3d || curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | bash`
Expected: both commands print a version when run afterward (`helm version`, `k3d version`).

- [ ] **Step 2: Create a throwaway k3d cluster with a mapped ingress port**

Run: `k3d cluster create cloudlite-dev --agents 1 --port "8888:80@loadbalancer" --wait`
Expected: cluster comes up; `kubectl get nodes` shows a server node and one agent node, both `Ready`. (`8888` is an arbitrary free host port mapped to the cluster's Traefik load balancer on port 80 — this is how the Ingress in Task 6 gets reached from outside the cluster in this sandbox.)

- [ ] **Step 3: Build the S3 and IAM images and import them into the cluster**

From the repo root:

```bash
docker build -t s3:0.1.0 services/s3
docker build -t iam:0.1.0 services/iam
k3d image import s3:0.1.0 iam:0.1.0 -c cloudlite-dev
```

Expected: both builds succeed (matches the images already proven to build in earlier sub-projects' `docker compose build` verification); `k3d image import` reports both images imported with no errors.

- [ ] **Step 4: Install the chart**

Run: `helm install cloudlite deploy/helm/cloudlite -f deploy/helm/cloudlite/values-dev.yaml`
Expected: `STATUS: deployed`, lists the resources created.

- [ ] **Step 5: Verify pods and PVCs**

Run: `kubectl get pods -n cloudlite`
Expected: `postgres-0`, an `iam-...` pod, and an `s3-...` pod all reach `1/1 Ready` within roughly 60-90 seconds (JVM warm-up plus the `initialDelaySeconds: 10` probe delay). If any pod doesn't reach Ready, run `kubectl describe pod -n cloudlite <pod-name>` and `kubectl logs -n cloudlite <pod-name>` to diagnose before proceeding — do not report this task DONE with a pod stuck in `CrashLoopBackOff` or `ImagePullBackOff`.

Run: `kubectl get pvc -n cloudlite`
Expected: both `postgres-data-postgres-0` and `s3-data` show `STATUS: Bound`.

- [ ] **Step 6: Verify both services are reachable through the ingress**

Run: `curl -s -o /dev/null -w "s3 /healthz: %{http_code}\n" -H "Host: s3.cloudlite.local" http://localhost:8888/healthz`
Expected: `s3 /healthz: 200`

Run: `curl -s -o /dev/null -w "iam /healthz: %{http_code}\n" -H "Host: iam.cloudlite.local" http://localhost:8888/healthz`
Expected: `iam /healthz: 200`

- [ ] **Step 7: Verify a real S3↔IAM round trip works inside the cluster** (proves `IAM_BASE_URL: http://iam:8081` in-cluster DNS resolution actually works, not just that each service is independently healthy)

Run:

```bash
curl -s -H "Host: iam.cloudlite.local" -X POST http://localhost:8888/users \
  -H "Content-Type: application/json" -d '{"username":"k3d-smoke-test"}'
```

Expected: `201` with a JSON body containing `"apiKey"`. Copy the returned `apiKey`, then:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Host: s3.cloudlite.local" -X PUT http://localhost:8888/smoke-test-bucket
```

Expected: without a valid `Authorization` header this returns `403` (`AccessDenied`) — proving `AuthInterceptor` is live and actually reaching the real in-cluster `iam` Service (a misconfigured `IAM_BASE_URL` would instead produce a `500 InternalError`, not a clean `403`). This is suffient proof for this task; a full authenticated round trip (exchange the API key for a JWT, retry the PUT with a Bearer token) is optional extra verification, not required to mark this task complete.

- [ ] **Step 8: Tear down the throwaway cluster**

Run: `helm uninstall cloudlite -n cloudlite && k3d cluster delete cloudlite-dev`
Expected: clean teardown, no lingering k3d containers (`docker ps | grep k3d` returns nothing).

- [ ] **Step 9: Write the platform doc**

Create `docs/platform/helm-charts.md`:

```markdown
# Helm charts

**Status:** built — an umbrella Helm chart (`deploy/helm/cloudlite/`)
packaging `services/s3` and `services/iam` as subcharts, plus a
hand-rolled Postgres StatefulSet, `fast-ssd`/`bulk-hdd` StorageClasses,
and a Traefik-routed Ingress. See
[`../superpowers/plans/2026-08-22-helm-charts.md`](../superpowers/plans/2026-08-22-helm-charts.md)
for what was built and
[`../superpowers/specs/2026-08-22-helm-charts-design.md`](../superpowers/specs/2026-08-22-helm-charts-design.md)
for the design.

## Scope

- `charts/s3/`, `charts/iam/` — per-service subcharts, each
  independently testable via `helm template charts/<service>`
- Postgres — a hand-rolled `StatefulSet` at the umbrella level (not a
  subchart — it isn't one of this project's own services)
- `fast-ssd`/`bulk-hdd` `StorageClass` resources
- One `Ingress`, Traefik-routed, `s3.cloudlite.local` /
  `iam.cloudlite.local`

## Validated against

A local k3d cluster (real k3s running in Docker) — see the
implementation plan's Task 7 for the exact commands. k3d ships the
same bundled Traefik ingress controller and `local-path` storage
provisioner as the real bare-metal target, so what validates here
should carry over to real hardware.

## Known simplification

Both `StorageClass`es currently share one `rancher.io/local-path`
provisioner instance, so they land on the same host directory in this
phase — proving the app-level contract (two independently-named,
independently-bindable storage classes), not physical SSD/HDD
separation. Achieving genuine physical separation on the real
bare-metal node requires either a second, independently-configured
`local-path-provisioner` instance or a different provisioner
altogether — an open item for whoever applies this chart to real
hardware.

## Out of scope

Sealed Secrets encryption (secrets here are plain/unsealed, dev-only
values), `fnrunner`/`web` subcharts (neither service exists yet), TLS
on the ingress, Postgres HA/replication, ArgoCD/CI-CD/observability/
chaos-test (separate, later sub-projects), and actually applying this
chart to the user's real bare-metal k3s node (a separate action the
user runs themselves, following this doc and the plan).
```

- [ ] **Step 10: Commit**

```bash
git add docs/platform/helm-charts.md
git commit -m "docs: add Helm charts platform doc"
```

