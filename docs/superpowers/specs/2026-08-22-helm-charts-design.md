# Helm Charts (Platform Layer, Part 1) — Design

Date: 2026-08-22
Status: Approved, not yet planned/implemented

## 1. Context

Per `architecture.md` §11, steps 1-3 are complete: `services/s3` and
`services/iam` are both built, merged, and wired together (S3 calls
IAM's `/authorize` on every request). Step 4, "Platform layer: k3s +
Helm + ArgoCD + CI/CD + Prometheus/Grafana + chaos test," bundles
several largely-independent infrastructure subsystems. Per the
brainstorming skill's own guidance for multi-subsystem requests, this
has been decomposed into separate sub-projects, each with its own
spec/plan/implementation cycle:

1. **Helm charts** (this document)
2. k3s node provisioning (Terraform/Ansible) — the user's own hardware
3. CI/CD (path-triggered GitHub Actions)
4. ArgoCD GitOps (syncs the Helm chart into the cluster)
5. Observability (Prometheus + Grafana + Loki)
6. Chaos test

This document specs sub-project 1: packaging `services/s3` and
`services/iam` (plus their shared Postgres dependency) as a Helm
umbrella chart, per `architecture.md` §9's already-documented repo
structure and §10's Helm chart notes.

**Environment note:** this build-and-validate work happens in a
sandboxed dev environment, not the user's actual bare-metal laptop
(`docs/decisions/0001-bare-metal-k3s.md`'s real target). Validation
uses a local **k3d** cluster (real k3s running in Docker) as a stand-in
— k3d ships the same bundled Traefik ingress controller and
`local-path` storage provisioner as the real target, so what validates
here should behave identically once applied to real hardware. Actually
running Terraform/Ansible against the user's physical machine is a
separate sub-project the user runs themselves.

## 2. Goals

- Package `services/s3` and `services/iam` as Helm subcharts
  (`charts/s3/`, `charts/iam/`) inside one umbrella chart
  (`deploy/helm/cloudlite/`), each independently testable via
  `helm template charts/<service>` (ADR 0006).
- Deploy Postgres (shared by both services) as a hand-rolled
  `StatefulSet` + `PersistentVolumeClaim` + `Service`, living at the
  umbrella-chart level (not a subchart — see §5 for why).
- Define the two `StorageClass` resources `architecture.md` §4/§7 call
  for (`fast-ssd`, `bulk-hdd`), both backed by the `rancher.io/local-path`
  provisioner k3s/k3d already bundle.
- Expose both services externally in-cluster via one `Ingress` resource
  routed through k3s's bundled Traefik controller
  (`s3.cloudlite.local`, `iam.cloudlite.local`).
- Establish the `values.yaml` key-naming convention `architecture.md`
  §10 calls out before it gets inconsistent across subcharts
  (`s3.persistence.size`, `iam.replicaCount`, etc.).
- Resource requests/limits and JVM `-Xmx` tuning match the server
  capacity budget in `architecture.md` §8 exactly.
- Secrets (DB password, JWT signing secret) are real Kubernetes
  `Secret` objects referenced via `envFrom.secretRef` — never inlined
  into `values.yaml` (ADR 0010).
- `helm install cloudlite` brings up the whole platform (Postgres, S3,
  IAM) in one command, and the result is validated end-to-end against
  a real k3d cluster in this sandbox (pods Ready, PVCs Bound, both
  services reachable through the ingress at `/healthz`).

## 3. Non-goals

- Sealed Secrets encryption of the `Secret` objects this sub-project
  creates. ADR 0010 already commits the project to Sealed Secrets as
  its eventual secrets story, but standing up that controller is a
  separate, later sub-project — this pass uses plain, unsealed
  `Secret` manifests with dev-only placeholder values (the same
  posture already used for `IAM_JWT_SECRET`'s dev default in
  `services/iam/application.yml`).
- `fnrunner`/`web` subcharts — neither service exists yet
  (`architecture.md` §9 lists them in the eventual repo structure, but
  building empty placeholder charts for non-existent services is
  scope creep, not scaffolding).
- TLS on the ingress — local/dev HTTP is sufficient; this project's
  interview narrative is backend/SRE depth, not certificate management.
- Postgres HA, replication, or multi-replica anything — a single
  instance matches this project's own explicit non-goal of
  multi-node/HA infrastructure at this hardware tier
  (`future-work.md`).
- Actually running Terraform/Ansible against the user's real hardware
  — a separate sub-project (#2 in the list above), and not something
  this session can do directly regardless.
- ArgoCD, CI/CD, observability, chaos test — separate, later
  sub-projects (#3-#6 above). This chart must exist and work standalone
  (`helm install`/`helm upgrade` by hand) before ArgoCD is layered on
  top of it in a later sub-project, per `architecture.md` §11's own
  "provisioning happens before ArgoCD is ever installed" note.

## 4. Architecture

```
deploy/helm/cloudlite/
├── Chart.yaml
├── values.yaml            # shared defaults
├── values-dev.yaml         # local/dev overrides (smaller PVC sizes)
├── charts/
│   ├── s3/                 # subchart: Deployment, Service, PVC
│   │   ├── Chart.yaml
│   │   ├── values.yaml
│   │   └── templates/{deployment,service,pvc,configmap}.yaml
│   └── iam/                 # subchart: Deployment, Service
│       ├── Chart.yaml
│       ├── values.yaml
│       └── templates/{deployment,service,configmap}.yaml
└── templates/
    ├── namespace.yaml        # single `cloudlite` namespace
    ├── storageclasses.yaml   # fast-ssd, bulk-hdd
    ├── ingress.yaml          # Traefik: s3.cloudlite.local, iam.cloudlite.local
    └── postgres/
        ├── statefulset.yaml  # 1 replica, fast-ssd PVC
        ├── service.yaml
        ├── configmap.yaml    # POSTGRES_DB (non-secret)
        └── secret.yaml       # POSTGRES_PASSWORD (dev-only plaintext)
```

`helm install cloudlite deploy/helm/cloudlite` creates the `cloudlite`
namespace and every resource above in one command. `charts/s3` and
`charts/iam` are each independently renderable
(`helm template deploy/helm/cloudlite/charts/s3`) for isolated
testing, per ADR 0006.

## 5. Components

### `charts/s3/`
- `Deployment`: 1 replica, image `s3:0.1.0` (built from the existing
  `services/s3/Dockerfile`), `resources.limits` = `1 vCPU / 1Gi`
  (`architecture.md` §8), `resources.requests` = half that
  (`0.5 vCPU / 512Mi`, per §8's "requests ≈ half of limits" note),
  `env` from a `ConfigMap` (`SERVER_PORT`, `S3_DATA_DIR`,
  `IAM_BASE_URL` pointed at the in-cluster `iam` Service DNS name,
  `JAVA_TOOL_OPTIONS: -Xmx768m`) plus `envFrom.secretRef` for
  `SPRING_DATASOURCE_PASSWORD`. `readinessProbe`/`livenessProbe`:
  `httpGet /healthz`, `initialDelaySeconds: 10` (JVM warm-up, per §8's
  JVM tuning notes — Go's near-instant startup assumption doesn't
  apply here).
- `Service`: `ClusterIP`, port 8080.
- `PersistentVolumeClaim`: `storageClassName: bulk-hdd`, mounted at
  `S3_DATA_DIR` — matches the `s3-data` volume already in
  `docker-compose.yml`.

### `charts/iam/`
- `Deployment`: 1 replica, image `iam:0.1.0`, `resources.limits` =
  `0.75 vCPU / 768Mi` (§8), requests ≈ half, `env`/`envFrom` supplying
  `SERVER_PORT`, `IAM_JWT_EXPIRY_SECONDS`, `JAVA_TOOL_OPTIONS:
  -Xmx512m`, plus `envFrom.secretRef` for `SPRING_DATASOURCE_PASSWORD`
  and `IAM_JWT_SECRET`. Same `/healthz` probe shape as S3.
- `Service`: `ClusterIP`, port 8081.
- No PVC — IAM writes nothing to local disk.

### Postgres (umbrella-level, not a subchart)
A `StatefulSet` rather than a subchart, because it isn't one of this
project's own services (it has no Dockerfile/application code here,
just the upstream `postgres:16-alpine` image) — `architecture.md` §9's
repo structure only lists `{s3, iam, fnrunner, web}` as subcharts, and
treating Postgres as a peer application chart would misrepresent it.
- `StatefulSet`: 1 replica, image `postgres:16-alpine` (matching
  `docker-compose.yml`), `resources.limits` = `1 vCPU / 1Gi` (§8),
  env from a `ConfigMap` (`POSTGRES_DB=cloudlite`,
  `POSTGRES_USER=cloudlite`) plus `envFrom.secretRef` for
  `POSTGRES_PASSWORD`.
- `PersistentVolumeClaim` (via the StatefulSet's `volumeClaimTemplates`):
  `storageClassName: fast-ssd`.
- `Service`: `ClusterIP`, port 5432 (no headless service needed at a
  single replica).
- `Secret`: `POSTGRES_PASSWORD` — plain/unsealed for this pass (see
  §3 Non-goals).

### Umbrella-level templates
- `namespace.yaml`: a single `cloudlite` `Namespace` — everything in
  this chart lives in it.
- `storageclasses.yaml`: two `StorageClass` resources,
  `provisioner: rancher.io/local-path` for both — `fast-ssd` and
  `bulk-hdd`, distinguished via `local-path-provisioner`'s per-class
  path configuration (a `ConfigMap` the provisioner itself reads,
  mapping each `StorageClass` name to a distinct host directory). In
  this sandbox's k3d cluster both resolve to different container-local
  paths (validates the *mechanism*, per `architecture.md` §4's
  SSD/HDD split); on real hardware the user points each path at their
  actual SSD- and HDD-mounted directories.
- `ingress.yaml`: one `Ingress`, `ingressClassName: traefik` (k3s's and
  k3d's bundled default), two host rules —
  `s3.cloudlite.local` → the `s3` Service, `iam.cloudlite.local` → the
  `iam` Service.

## 6. Values-key convention

Root-level per-component keys in `values.yaml`, matching the exact
pattern `architecture.md` §10 names:

```yaml
s3:
  replicaCount: 1
  image:
    repository: s3
    tag: "0.1.0"
  resources:
    limits: { cpu: "1", memory: "1Gi" }
    requests: { cpu: "500m", memory: "512Mi" }
  persistence:
    size: 20Gi
    storageClassName: bulk-hdd

iam:
  replicaCount: 1
  image:
    repository: iam
    tag: "0.1.0"
  resources:
    limits: { cpu: "750m", memory: "768Mi" }
    requests: { cpu: "375m", memory: "384Mi" }

postgres:
  image:
    repository: postgres
    tag: "16-alpine"
  resources:
    limits: { cpu: "1", memory: "1Gi" }
    requests: { cpu: "500m", memory: "512Mi" }
  persistence:
    size: 10Gi
    storageClassName: fast-ssd
```

`values-dev.yaml` layers only what differs locally over these shared
defaults (e.g. smaller `persistence.size` values for a laptop/sandbox
cluster) — never forking the chart per environment, per ADR 0006.

## 7. Secrets

`services/postgres-credentials` and `services/iam-jwt-secret` (or a
single combined `Secret` — left to the plan) hold `POSTGRES_PASSWORD`
and `IAM_JWT_SECRET` respectively, referenced via `envFrom.secretRef`
in the relevant `Deployment`/`StatefulSet` specs. No secret value is
ever written into `values.yaml` or any `ConfigMap` — satisfying ADR
0010's rule even though the `Secret` objects themselves are plain
(unsealed) for this pass, per §3's Non-goals.

## 8. Testing

- `helm lint deploy/helm/cloudlite` and
  `helm lint deploy/helm/cloudlite/charts/{s3,iam}` — static chart
  validation.
- `helm template` on the umbrella chart and each subchart independently
  — confirms rendered manifests are well-formed Kubernetes YAML,
  without needing a live cluster.
- Real end-to-end validation against a k3d cluster in this sandbox:
  `helm install cloudlite deploy/helm/cloudlite -f values-dev.yaml`,
  then `kubectl get pods -n cloudlite` (all Ready), `kubectl get pvc -n
  cloudlite` (all Bound), and a `curl` through the Traefik ingress
  (`Host: s3.cloudlite.local` / `Host: iam.cloudlite.local`) hitting
  `/healthz` on both services and getting `200`.
- `helm uninstall cloudlite` cleanly tears everything down (no
  orphaned PVCs left `Bound` to a deleted claim, unless intentionally
  retained — left to the plan whether the `PersistentVolume` reclaim
  policy is `Delete` or `Retain` for this dev pass).

## 9. Open items for the implementation plan

- Exact image tag/repository strategy for `helm install` to actually
  pull the right images in a k3d cluster (k3d needs images either
  pushed to a registry it can reach, or imported directly via
  `k3d image import`) — left to the plan; this is sandbox-validation
  plumbing, not something the real bare-metal deployment needs (there,
  images come from wherever step 3's CI/CD sub-project publishes them).
- Whether `POSTGRES_PASSWORD` and `IAM_JWT_SECRET` live in one combined
  `Secret` or two separate ones — no behavioral difference, left to
  the plan.
- Exact `PersistentVolume` reclaim policy for the dev pass (`Delete` vs
  `Retain`) — left to the plan; recommend `Delete` for a
  sandbox-validation cluster that gets torn down repeatedly, revisit
  for the real bare-metal deployment.
