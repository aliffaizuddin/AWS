# ArgoCD GitOps (Platform Layer, Part 4) — Design

Date: 2026-08-24
Status: Approved, not yet planned/implemented

## 1. Context

Per `architecture.md` §11, platform-layer step 4's first two sub-projects
are complete and merged: Helm charts (PR #13) gave
`deploy/helm/cloudlite/` a working umbrella chart, and CI/CD (PR #18)
gave `services/s3`/`services/iam` path-triggered GitHub Actions that
build/test on every PR and, on a push to `main`, publish real images to
`ghcr.io/aliffaizuddin/aws/{s3,iam}` tagged by short git SHA — confirmed
working end-to-end against the real repo.

Per the brainstorming skill's guidance for multi-subsystem requests,
step 4 was decomposed into separate sub-projects:

1. Helm charts — done (PR #13)
2. k3s node provisioning (Terraform/Ansible) — the user's own hardware
3. CI/CD (path-triggered GitHub Actions) — done (PR #18)
4. **ArgoCD GitOps** (syncs the Helm chart into the cluster) — this document
5. Observability (Prometheus + Grafana + Loki)
6. Chaos test

ArgoCD was chosen next because it's the piece that actually closes the
loop `architecture.md` §7's infrastructure diagram describes (`Git repo
→ CI pipeline → Image registry → ArgoCD (GitOps) → k3s cluster`) — CI/CD
now produces a real, addressable image for it to eventually reference.

**Environment note:** validated against a local k3d cluster (real k3s
running in Docker), the same sandbox stand-in used for the Helm chart
sub-project — this session cannot reach the user's physical hardware.

**A real blocker surfaced during brainstorming, not a workaround:**
ArgoCD's `repo-server` only ever renders the chart from what's
committed in git — it has no access to the gitignored, locally-supplied
`deploy/helm/cloudlite/values-secrets.yaml` a human uses with `helm
install -f values-secrets.yaml`. Without a fix, ArgoCD's render of
`templates/postgres/secret.yaml`/`charts/iam/templates/secret.yaml`
would always fail their `required()` guards. `docs/decisions/0010-sealed-secrets.md`
already commits this project to Sealed Secrets as the mechanism that
lets real secret values live safely in git — this is the point where
that decision stops being aspirational and becomes load-bearing, so
standing up Sealed Secrets is now part of this sub-project rather than
a separately-deferred one.

## 2. Goals

- ArgoCD installed into its own `argocd` namespace via a **trimmed**
  version of the official upstream install manifest (pinned to
  `v3.5.1`) — `dex-server`, `notifications-controller`, and
  `applicationset-controller` removed (no SSO, no alerting, no
  ApplicationSet use here), and explicit `resources.requests/limits`
  added to the remaining components
  (`application-controller`/`repo-server`/`server`/`redis`), matching
  this project's existing convention of never leaving a component
  resource-unbounded.
- Sealed Secrets controller installed the same way (official upstream
  manifest, pinned to `v0.39.1`) into a `sealed-secrets` namespace,
  also with explicit resource limits.
- The two existing plain `Secret` templates converted to `SealedSecret`
  resources (`bitnami.com/v1alpha1`), sealed with `kubeseal` against
  the target cluster's public key — safe to commit as ciphertext,
  decrypted in-cluster by the controller into the same `Secret` objects
  the Deployments already reference via `envFrom.secretRef`. No
  application-facing behavior changes; only how the Secret's contents
  reach the cluster changes.
- `deploy/helm/cloudlite/values-secrets.yaml.example` and its
  `.gitignore` entry removed, along with the now-unused
  `postgres.password`/`jwt.secret` value keys and their `required()`
  guards — the mechanism they supported no longer exists.
- One ArgoCD `Application` resource (`deploy/argocd/applications/cloudlite.yaml`)
  pointing at this repo's `deploy/helm/cloudlite` path on `main`, with
  `syncPolicy.automated: {prune: true, selfHeal: true}` and
  `syncOptions: [CreateNamespace=true]` — matching the
  `-n cloudlite --create-namespace` pattern already established for
  manual installs.
- ArgoCD's read access to this private GitHub repo is a one-time
  bootstrap credential (a fine-grained, read-only, repo-scoped PAT),
  applied directly via `kubectl apply` before the `Application` exists
  — the same category of one-time step as installing ArgoCD itself,
  not something ArgoCD's own GitOps loop manages. Documented via a
  committed `.example` template with a placeholder, the real value
  gitignored (a new `.gitignore` entry for
  `deploy/argocd/repo-credentials-secret.yaml`) — same pattern as
  `values-secrets.yaml.example` was.
- `docs/architecture.md` §8's capacity budget table gets real rows for
  ArgoCD's trimmed component set and the Sealed Secrets controller,
  finally matching what `docs/decisions/0007-argocd-gitops.md` already
  claims ("accounted for in the server capacity budget").
- Validated end-to-end against a real k3d cluster: ArgoCD clones this
  actual repo, syncs `deploy/helm/cloudlite` with zero manual `helm
  install`/`upgrade` commands, decrypts both `SealedSecret`s into
  working `Secret`s, and all pods reach Ready. Then the definitive
  GitOps proof: commit a real, published `ghcr.io` SHA tag as
  `s3.image.tag` in `values.yaml`, `git push`, and confirm ArgoCD
  detects and auto-syncs the change within its poll interval — no
  command from the operator beyond the push.

## 3. Non-goals

- Automating the image-tag bump itself (e.g. ArgoCD Image Updater).
  Bumping `values.yaml`'s `image.tag` fields stays a human edit +
  commit for now — a clearly-scoped future add-on, not blocking this
  sub-project's GitOps loop from being real and working.
- HashiCorp Vault or any secrets manager beyond Sealed Secrets —
  `docs/decisions/0010-sealed-secrets.md` already settled this;
  `future-work.md` already defers Vault.
- `deploy/argocd/applications/` containing more than one `Application`
  (no app-of-apps pattern) — one umbrella chart, one Application; a
  second becomes worth it only if a second independently-synced chart
  exists.
- ArgoCD's own install being itself GitOps-managed (self-management /
  "app of apps for ArgoCD") — its installation is a one-time cluster
  bootstrap step, the same category as k3s node provisioning
  (`docs/decisions/0007-argocd-gitops.md`'s own framing), not part of
  the steady-state deploy loop.
- TLS/ingress exposure of the ArgoCD UI — cluster-internal access
  (`kubectl port-forward`) is sufficient for this project's scope; no
  interview narrative depends on a public ArgoCD dashboard.
- Actually running this against the user's real bare-metal node — a
  separate action the user runs themselves, same framing as every
  prior sub-project.
- Observability, chaos test — separate, later sub-projects (#5, #6).

## 4. Architecture

```
deploy/argocd/
├── install/
│   ├── argocd-install.yaml          # trimmed upstream v3.5.1 manifest,
│   │                                 # + resources on the 4 remaining components
│   └── sealed-secrets-install.yaml  # upstream v0.39.1 manifest + resources
├── repo-credentials-secret.yaml.example  # committed template; real file gitignored
└── applications/
    └── cloudlite.yaml                # the one ArgoCD Application

deploy/helm/cloudlite/
├── templates/postgres/
│   └── sealedsecret.yaml            # replaces secret.yaml
└── charts/iam/templates/
    └── sealedsecret.yaml            # replaces secret.yaml
```

Bootstrap order (one-time, not GitOps-managed): install Sealed Secrets
→ install ArgoCD → apply the repo-credentials `Secret` into `argocd` →
apply `deploy/argocd/applications/cloudlite.yaml`. From that point on,
ArgoCD's own reconciliation loop is the only thing touching the
`cloudlite` namespace.

## 5. Components

### ArgoCD install (`deploy/argocd/install/argocd-install.yaml`)
Derived from `https://raw.githubusercontent.com/argoproj/argo-cd/v3.5.1/manifests/install.yaml`
with `argocd-dex-server`, `argocd-notifications-controller`, and
`argocd-applicationset-controller` Deployments (and their
service/RBAC objects) removed, and `resources` added to the four kept:

| Component | Requests | Limits |
|---|---|---|
| `argocd-application-controller` | 250m / 256Mi | 500m / 512Mi |
| `argocd-repo-server` | 100m / 128Mi | 300m / 384Mi |
| `argocd-server` | 50m / 64Mi | 150m / 192Mi |
| `argocd-redis` | 50m / 64Mi | 100m / 128Mi |
| **ArgoCD total** | **450m / 512Mi** | **1.05 vCPU / 1.2Gi** |

(Numbers above are a starting budget; the plan measures real usage
against the k3d validation cluster via `kubectl top pods -n argocd` and
corrects §8's table with actual figures if they differ meaningfully.)

### Sealed Secrets install (`deploy/argocd/install/sealed-secrets-install.yaml`)
`https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.39.1/controller.yaml`,
with `resources: {requests: {cpu: 50m, memory: 64Mi}, limits: {cpu: 100m, memory: 128Mi}}`
added to its single Deployment.

### `SealedSecret` templates (replace the two plain `Secret` templates)
`templates/postgres/sealedsecret.yaml` and
`charts/iam/templates/sealedsecret.yaml`: `apiVersion: bitnami.com/v1alpha1`,
`kind: SealedSecret`, hardcoded `metadata.namespace: cloudlite` (Sealed
Secrets' default "strict" scope cryptographically binds ciphertext to a
specific namespace+name at seal time — this chart already standardizes
on the single `cloudlite` namespace, so hardcoding here matches
existing chart conventions rather than introducing a new constraint).
`spec.encryptedData` holds the real ciphertext, generated once via
`kubeseal` against whichever cluster is the current target (the k3d
sandbox for this sub-project's validation) and committed directly —
this is the artifact that used to be `values-secrets.yaml`, except now
it's genuinely safe to commit. Re-pointing at a different cluster (a
fresh k3d instance, or eventually the real bare-metal node) means
re-sealing and re-committing; this is Sealed Secrets' normal operating
model, not a defect to work around.

### `deploy/argocd/applications/cloudlite.yaml`
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: cloudlite
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/aliffaizuddin/AWS.git
    targetRevision: main
    path: deploy/helm/cloudlite
    helm:
      valueFiles:
        - values.yaml
        - values-dev.yaml
  destination:
    server: https://kubernetes.default.svc
    namespace: cloudlite
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```
`values-dev.yaml` is used for the sandbox validation pass, matching how
it was already used for manual `helm install` during the Helm chart
sub-project; the real bare-metal deployment would point at a
`values-prod.yaml` or similar the user creates when they get there —
left to that future action, not this sub-project.

### `deploy/argocd/repo-credentials-secret.yaml.example`
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: cloudlite-repo-creds
  namespace: argocd
  labels:
    argocd.argoproj.io/secret-type: repository
stringData:
  type: git
  url: https://github.com/aliffaizuddin/AWS.git
  username: CHANGE-ME-github-username
  password: CHANGE-ME-fine-grained-read-only-PAT
```
Copied to `deploy/argocd/repo-credentials-secret.yaml` (gitignored,
following the exact `values-secrets.yaml` precedent) and applied once
via `kubectl apply -n argocd -f ...` as part of the bootstrap sequence
in §4 — before the `Application` exists, since ArgoCD needs read access
to clone the repo before it can do anything with it.

## 6. Capacity budget update (`docs/architecture.md` §8)

Two new rows, using the totals from §5's tables:

| Pod/Component | Language | Resource limit |
|---|---|---|
| ArgoCD (trimmed: controller + repo-server + server + redis) | — | ~1.05 vCPU · ~1.2Gi |
| Sealed Secrets controller | — | 100m · 128Mi |

New grand total (limits, burst): **~5.4 vCPU · ~5.0Gi** — meaningfully
past the "available for pods" ~3 core / ~10Gi guidance on a 4-core
host, but per §8's own already-stated reasoning, limits are burst
ceilings, not concurrent-usage guarantees, and the existing total
already ran past the "safe" 3-core line before this sub-project. The
plan corrects these numbers with real `kubectl top` measurements from
the k3d validation pass.

## 7. Testing

- Static: none of `deploy/argocd/`'s manifests have a meaningful
  "unit test" — correctness is validated by applying them for real.
- Real end-to-end validation against a k3d cluster (this sandbox's
  stand-in, per the Helm chart sub-project's precedent):
  1. Install Sealed Secrets, confirm its controller pod is Ready.
  2. `kubeseal` the two real secret values against this cluster's
     public key, commit the resulting `SealedSecret` manifests,
     confirm `kubectl get secret` shows the decrypted `Secret` objects
     with the right keys populated.
  3. Install the trimmed ArgoCD manifest, confirm all four kept
     components reach Ready with `kubectl top pods -n argocd` numbers
     sane against §6's budget.
  4. Apply the repo-credentials `Secret`, then the `Application` —
     confirm ArgoCD clones this real repo (not a local path) and syncs
     `deploy/helm/cloudlite` with zero manual `helm` commands: pods
     Ready, PVCs Bound, `/healthz` reachable through the existing
     ingress — the same acceptance bar the Helm chart sub-project used.
  5. The GitOps proof: edit `values.yaml`'s `s3.image.tag` to a real
     `ghcr.io/aliffaizuddin/aws/s3` SHA tag published by the CI/CD
     sub-project, commit, push, and confirm ArgoCD's next
     reconciliation (within its default poll interval, or triggered
     via `argocd app sync` if waiting the full interval isn't
     practical) rolls the `s3` Deployment to that image with no
     `kubectl`/`helm` command run directly.
  6. Confirm drift correction: `kubectl scale` or `kubectl edit` a
     synced resource directly, and confirm ArgoCD's `selfHeal` reverts
     it — the concrete proof that "git is the single source of truth"
     (`docs/decisions/0007-argocd-gitops.md`) is real, not aspirational.

## 8. Open items for the implementation plan

- Exact `kubectl top`-measured resource numbers for ArgoCD's four
  components and the Sealed Secrets controller, to correct §6's
  starting-budget estimates — left to the plan's validation task.
- Whether `values-dev.yaml` needs any new keys for the ArgoCD-managed
  install path (e.g. if a value previously only supplied via
  `values-secrets.yaml` needs a non-secret dev-only counterpart) — left
  to the plan; expected to be none, since the only two secrets this
  chart has are the ones becoming `SealedSecret`s.
- Whether to wait out ArgoCD's default 3-minute poll interval during
  Step 5's validation or force an immediate sync via `argocd app sync`
  — left to the plan; either proves the same thing, forcing it is just
  faster for the sandbox pass.
- Exact RBAC (ServiceAccount/ClusterRole) trimming, if any, beyond
  removing the three unused Deployments — left to the plan; the
  trimmed components' own RBAC requirements are unchanged by removing
  their unrelated siblings.
