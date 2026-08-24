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
  `deploy/helm/cloudlite` on `main`. Renders with `values.yaml` +
  `values-dev.yaml` — the same dev-sized overrides (smaller PVCs) used
  for every sandbox `helm install` in the prior sub-projects. A real
  bare-metal deployment should point this at a `values-prod.yaml` (or
  similar) the deployer creates, not this dev file.
- Repo access: a read-only SSH deploy key (not the fine-grained PAT the
  design spec originally called for — creating one non-interactively
  isn't possible in this environment; a deploy key is the automatable
  equivalent, same purpose and lifecycle).

## Bootstrap order (one-time, not GitOps-managed)

Every command below is one-time, run manually against whichever cluster
is the target — none of this is git-tracked reconciliation, since
ArgoCD doesn't exist yet to do it.

1. **Install Sealed Secrets:**
   ```bash
   kubectl create namespace sealed-secrets
   kubectl apply -f deploy/argocd/install/sealed-secrets-install.yaml
   kubectl rollout status deployment/sealed-secrets-controller -n sealed-secrets
   ```
2. **Re-seal both secrets against this specific cluster** — `SealedSecret`
   ciphertext is cryptographically bound to the sealing cluster's key
   (see "Known operational properties" below), so the ciphertext
   already committed in this repo only decrypts on the k3d cluster it
   was validated against. Generate real values and reseal for a new
   cluster:
   ```bash
   POSTGRES_PASSWORD=$(openssl rand -base64 24)
   IAM_JWT_SECRET=$(openssl rand -base64 32)
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
   kubeseal --format=yaml --controller-namespace=sealed-secrets \
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
   kubeseal --format=yaml --controller-namespace=sealed-secrets \
     < /tmp/plain-iam-secret.yaml \
     > deploy/helm/cloudlite/charts/iam/templates/sealedsecret.yaml

   rm -f /tmp/plain-postgres-secret.yaml /tmp/plain-iam-secret.yaml
   git add deploy/helm/cloudlite/templates/postgres/sealedsecret.yaml \
           deploy/helm/cloudlite/charts/iam/templates/sealedsecret.yaml
   git commit -m "chore(argocd): re-seal secrets for <this cluster>"
   ```
   `--controller-namespace=sealed-secrets` is required — it isn't the
   `kubeseal` default (`kube-system`), since Sealed Secrets was
   deliberately installed into its own namespace here.
3. **Install ArgoCD:**
   ```bash
   kubectl create namespace argocd
   kubectl apply -n argocd -f deploy/argocd/install/argocd-install.yaml
   kubectl rollout status statefulset/argocd-application-controller -n argocd
   kubectl rollout status deployment/argocd-repo-server -n argocd
   kubectl rollout status deployment/argocd-server -n argocd
   kubectl rollout status deployment/argocd-redis -n argocd
   ```
   `-n argocd` on the `apply` is required, not optional — none of the
   59 upstream objects declare their own `metadata.namespace`, but the
   ClusterRoleBindings hardcode `subjects[].namespace: argocd`, so
   applying without `-n argocd` silently creates the ServiceAccounts in
   the wrong namespace and leaves RBAC broken.
4. **Apply the repo-credentials `Secret`** — copy
   `deploy/argocd/repo-credentials-secret.yaml.example` to
   `deploy/argocd/repo-credentials-secret.yaml` (gitignored), fill in a
   real read-only SSH deploy key's private half, then:
   ```bash
   kubectl apply -f deploy/argocd/repo-credentials-secret.yaml
   ```
5. **Apply the Application:**
   ```bash
   kubectl apply -f deploy/argocd/applications/cloudlite.yaml
   ```

From that point on, ArgoCD's own reconciliation loop is the only thing
that should touch the `cloudlite` namespace.

**Reaching the ArgoCD UI** (not exposed via ingress — see Out of scope):
```bash
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
kubectl -n argocd port-forward svc/argocd-server 8080:443
```
Then open `https://localhost:8080`, log in as `admin` with that password.

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
- **The chart is now pinned to the `cloudlite` namespace, not
  install-time-configurable:** Sealed Secrets' default "strict" scope
  cryptographically binds ciphertext to a specific namespace+name, so
  `templates/postgres/sealedsecret.yaml`/`charts/iam/templates/sealedsecret.yaml`
  hardcode `namespace: cloudlite` rather than templating
  `{{ .Release.Namespace }}` like the rest of the chart does. This
  means `helm install ... -n <anything-other-than-cloudlite>` now
  renders Deployments into that namespace while both `SealedSecret`s
  (and their derived `Secret`s) stay in `cloudlite` — the pods can't
  find their secrets. Installing to any namespace other than
  `cloudlite` requires re-sealing both secrets with that namespace
  baked in first. `docs/platform/helm-charts.md`'s "any namespace"
  claim predates this and is corrected there too (see Fix below).
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
