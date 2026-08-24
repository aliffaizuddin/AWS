# ArgoCD GitOps

**Status:** built — a trimmed, resource-budgeted ArgoCD install (4 of
7 stock components: application-controller, repo-server, server,
redis) plus Sealed Secrets, syncing `deploy/helm/cloudlite` from this
repo automatically. See
[`../superpowers/plans/2026-08-24-argocd.md`](../superpowers/plans/2026-08-24-argocd.md)
for what was built and
[`../superpowers/specs/2026-08-24-argocd-design.md`](../superpowers/specs/2026-08-24-argocd-design.md)
for the design.

## Scope

- `deploy/argocd/install/argocd-install.yaml` — ArgoCD v3.5.1, trimmed
  (no dex/notifications/applicationset-controller — no SSO, no
  alerting, no ApplicationSet use here), with explicit
  `resources.requests/limits` on all 4 kept components.
- `deploy/argocd/install/sealed-secrets-install.yaml` — Sealed Secrets
  v0.39.1, installed into its own `sealed-secrets` namespace (the
  upstream manifest defaults to `kube-system`) with explicit resource
  limits.
- `deploy/helm/cloudlite`'s two secrets (`postgres-credentials`,
  `iam-jwt-secret`) are now `SealedSecret` resources — ciphertext safe
  to commit, decrypted in-cluster by the controller. This replaces
  `values-secrets.yaml`/`.example` entirely; there is no more
  `required()` guard on either secret.
- `deploy/argocd/applications/cloudlite.yaml` — the one `Application`,
  `syncPolicy.automated: {prune: true, selfHeal: true}`, watching
  `deploy/helm/cloudlite` on `main`.
- Repo access: a read-only SSH deploy key (not the fine-grained PAT the
  design spec originally called for — creating one non-interactively
  isn't possible in this environment; a deploy key is the automatable
  equivalent, same purpose and lifecycle).

## Bootstrap order (one-time, not GitOps-managed)

Install Sealed Secrets → install ArgoCD → apply the repo-credentials
`Secret` into `argocd` → apply
`deploy/argocd/applications/cloudlite.yaml`. From that point on,
ArgoCD's own reconciliation loop is the only thing that should touch
the `cloudlite` namespace.

## Validated against

A local k3d cluster (real k3s in Docker), the same sandbox stand-in
used for the Helm chart sub-project. Confirmed for real: ArgoCD clones
this actual GitHub repo (not a local path) and syncs the chart with
zero manual `helm` commands; both `SealedSecret`s decrypt to the
correct values; a real `git push` bumping `values.yaml`'s image tag
drives an automatic pod rollout with no `kubectl`/`helm` command
involved; and `syncPolicy.automated.selfHeal` reverts a manually
`kubectl scale`d Deployment back to what git specifies. `kubectl top
pods -n argocd` (metrics-server was available) showed idle-state usage
well under the configured budget: application-controller 1m CPU/28Mi,
redis 5m/9Mi, repo-server 1m/25Mi, server 1m/45Mi — with no sustained
reconciliation load exercised, so these are a floor, not a ceiling; the
capacity budget table in `architecture.md` §8 is sized off the
configured requests/limits, not these idle numbers.

## Known operational properties (not defects)

- **Re-sealing on cluster change:** `SealedSecret` ciphertext is
  cryptographically bound to the sealing cluster's key. Pointing this
  chart at a different cluster (a fresh k3d instance, or the real
  bare-metal node) means re-running `kubeseal` and committing the new
  ciphertext — this is how Sealed Secrets is designed to work, not a
  bug to fix.
- **The GitOps-loop-proof step used a locally-built, `k3d
  image import`-ed image, not the real `ghcr.io/aliffaizuddin/aws/s3`
  image** — pulling the real private GHCR image needs a registry pull
  credential, and creating one (a token with `read:packages` scope)
  also requires interactive browser auth, unavailable in this sandbox.
  The sync mechanism this proves is identical either way; wiring up a
  real registry pull credential (or making the GHCR packages public)
  is an open item for whoever deploys this to real hardware.
- **`applicationsets.argoproj.io` CRD fails plain `kubectl apply`:**
  applying `argocd-install.yaml` with plain `kubectl apply` fails on
  this one CRD because its schema exceeds kubectl's client-side
  `last-applied-configuration` annotation 256KB size limit. Harmless
  here since ApplicationSets is intentionally unused, but if it's ever
  actually needed later, apply that one object with `kubectl apply
  --server-side` or `kubectl create` instead.

## Out of scope

Automating the image-tag bump (e.g. ArgoCD Image Updater) — stays a
human edit + commit for now. HashiCorp Vault or any secrets manager
beyond Sealed Secrets. An app-of-apps pattern (only one `Application`
exists). ArgoCD's own install being itself GitOps-managed. TLS/ingress
exposure of the ArgoCD UI. Observability, chaos test (separate, later
sub-projects). Actually running this against the user's real
bare-metal node.
