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

## Known issues from sandbox validation

- **cgroup v1 hosts:** the sandbox host used for Task 7 runs Docker with
  `Cgroup Version: 1` (`docker info | grep -i cgroup`), and k3d's default
  k3s image (`v1.35.5-k3s1` at the time of writing) failed to start its
  kubelet there (`"kubelet is configured to not run on a host using cgroup
  v1"`). Validation required pinning an older k3s image:
  `k3d cluster create <name> --image rancher/k3s:v1.28.15-k3s1 ...`. This
  likely won't affect a fresh real bare-metal install (a modern kernel
  there should already be on cgroup v2), but if `k3d cluster create` fails
  with a kubelet startup error, check `docker info | grep -i cgroup` first.
- **Namespace flags on install vs. uninstall:** the implementation plan's
  Task 7 commands install with no `-n` flag
  (`helm install cloudlite deploy/helm/cloudlite -f ...`), which tracks the
  Helm release under the `default` namespace even though the chart's
  resources deploy into `cloudlite` (via `global.namespace`). That's fine
  for disposable sandbox validation (tearing down the whole k3d cluster
  hides the mismatch), but it's a trap for a real deployment — a later
  `helm uninstall cloudlite -n cloudlite` fails with `release: not found`,
  with no cluster-delete escape hatch. For a real (non-disposable) install,
  use `-n`/`--create-namespace` consistently on both sides instead:
  `helm install cloudlite deploy/helm/cloudlite -n cloudlite --create-namespace -f deploy/helm/cloudlite/values-dev.yaml`
  and, correspondingly, `helm uninstall cloudlite -n cloudlite`.

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
