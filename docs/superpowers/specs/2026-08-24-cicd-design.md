# CI/CD (Platform Layer, Part 3) — Design

Date: 2026-08-24
Status: Approved, not yet planned/implemented

## 1. Context

Per `architecture.md` §11, steps 1-3 are complete, and platform-layer
step 4's first sub-project (Helm charts) is also complete and merged
(PR #13) — `deploy/helm/cloudlite/` is a working umbrella chart with
`charts/s3`/`charts/iam` subcharts, a hand-rolled Postgres
`StatefulSet`, `StorageClass`es, and a Traefik `Ingress`, validated
end-to-end against a local k3d cluster.

Per the brainstorming skill's guidance for multi-subsystem requests,
step 4 was decomposed into separate sub-projects, each with its own
spec/plan/implementation cycle:

1. Helm charts — done (PR #13)
2. k3s node provisioning (Terraform/Ansible) — the user's own hardware
3. **CI/CD** (path-triggered GitHub Actions) — this document
4. ArgoCD GitOps (syncs the Helm chart into the cluster)
5. Observability (Prometheus + Grafana + Loki)
6. Chaos test

CI/CD was chosen next (ahead of #2, #4, #5, #6) specifically because
ArgoCD's GitOps loop (#4) needs CI to have already built and pushed a
real, addressable image before ArgoCD can pull and deploy it — CI/CD
is a hard dependency of ArgoCD, not an independent sibling.

This document specs sub-project 3: `.github/workflows/` per
`docs/decisions/0008-github-actions-cicd.md`'s path-triggered,
per-service workflow decision.

**Environment note:** unlike the Helm chart sub-project (which needed
a k3d stand-in because this sandbox cannot reach the user's bare-metal
hardware), CI/CD validates for real. This is a real, private GitHub
repository — pushing a throwaway branch and opening a PR against it
exercises the actual GitHub Actions runners, the actual `ghcr.io`
registry, and actual PR check results. No stand-in is needed.

## 2. Goals

- `.github/workflows/ci-java-service.yml`: one reusable workflow
  (`workflow_call`), parameterized by service name/path/image name,
  implementing the shared Java/Maven/Spring Boot pipeline: checkout →
  JDK 21 setup (with Maven dependency caching) → `mvn -B test` → on a
  push to `main` only, build the service's existing `Dockerfile` and
  push it to `ghcr.io`, tagged with the short git SHA.
- `.github/workflows/ci-s3.yml` and `.github/workflows/ci-iam.yml`:
  thin, path-triggered callers of `ci-java-service.yml`
  (`paths: ["services/s3/**"]` / `["services/iam/**"]`), matching ADR
  0008's naming and per-service isolation exactly.
- `.github/workflows/ci-helm.yml`: independent, path-triggered
  (`paths: ["deploy/helm/**"]`) static validation — `helm lint` and
  `helm template` across the umbrella chart and both subcharts, on
  every PR and push that touches the chart. No cluster, no registry.
- Images land in GitHub Container Registry
  (`ghcr.io/aliffaizuddin/aws/<service>:<short-sha>`), authenticated
  via the workflow's built-in `GITHUB_TOKEN` — no extra registry
  secret to create or rotate.
- On a pull request: build + test only, no image push. On a push to
  `main`: build + test + push. Keeps the registry populated only with
  images that actually reached `main`, and keeps PR feedback fast.
- `mvn test` genuinely exercises each service's Testcontainers-backed
  integration suite (a real, ephemeral Postgres container per test
  run) — GitHub's Ubuntu runners have Docker preinstalled, so this
  needs no special runner configuration.
- Validated for real: a throwaway branch + PR against each path
  (`services/s3/**`, `services/iam/**`, `deploy/helm/**`) confirms each
  workflow fires only for its own path, tests genuinely run, and (on a
  push to `main`) the image genuinely lands in `ghcr.io`.

## 3. Non-goals

- `ci-fnrunner.yml` / `ci-web.yml` — neither service exists yet
  (`architecture.md` §9 lists them for the eventual repo structure,
  but an empty pipeline for a non-existent service is scope creep, not
  scaffolding). Added when those services are built, per ADR 0008's
  naming convention.
- Auto-bumping `deploy/helm/cloudlite/values.yaml`'s image tags and
  committing that back to `main` (a real "CD"/GitOps step). That loop
  — new image in the registry → deploy manifest updated → cluster
  synced — is exactly ArgoCD's job (sub-project 4). Building it here
  would duplicate work that gets redone once ArgoCD lands. This
  sub-project stops at "a trustworthy, SHA-tagged image exists in the
  registry."
- Docker Hub, a self-hosted registry, or any registry other than
  `ghcr.io` — decided in favor of GHCR for its zero-extra-secret
  `GITHUB_TOKEN` auth and its proximity to a private repo already
  hosted on GitHub.
- Image signing/provenance attestation (e.g. cosign, SBOM generation).
  Not part of this project's interview narrative and not requested;
  revisit only if a specific need surfaces.
- Configuring branch-protection "required checks" in the repo's GitHub
  settings. That's a one-time UI/API setting outside this sub-project's
  file changes — worth doing once these workflows exist, but not a
  deliverable of this spec.
- Deploying anything. This sub-project only builds and publishes
  images; nothing here touches the k3s cluster.

## 4. Architecture

```
.github/workflows/
├── ci-java-service.yml   # reusable (workflow_call): test, and on
│                         # push-to-main, build+push
├── ci-s3.yml             # paths: services/s3/**  → calls ci-java-service.yml
├── ci-iam.yml            # paths: services/iam/**  → calls ci-java-service.yml
└── ci-helm.yml           # paths: deploy/helm/**  → helm lint/template
```

A PR touching `services/s3/**` triggers `ci-s3.yml`, which calls
`ci-java-service.yml` with `service-name: s3`. Tests run; no image is
built or pushed. Merging to `main` re-triggers the same workflow on
the push event; this time the build+push steps run too, producing
`ghcr.io/aliffaizuddin/aws/s3:<short-sha>`. `ci-iam.yml` is the
identical shape for IAM. A `deploy/helm/**` change (PR or push) fires
only `ci-helm.yml`, independent of whether any service code changed.

## 5. Components

### `ci-java-service.yml` (reusable workflow)

- Trigger: `on: workflow_call`, inputs `service-name` (string, e.g.
  `s3`), `service-path` (string, e.g. `services/s3`), `image-name`
  (string, e.g. `ghcr.io/aliffaizuddin/aws/s3`).
- `permissions: { contents: read, packages: write }` — the minimum
  needed to push to GHCR; nothing else.
- Steps:
  1. `actions/checkout@v4`
  2. `actions/setup-java@v4` with `distribution: temurin`,
     `java-version: '21'` (matching `services/{s3,iam}/pom.xml`'s
     `<java.version>`), `cache: maven`
  3. `mvn -B test` in `service-path` — runs unit tests and the
     Testcontainers-backed integration tests
     (`S3ApplicationIntegrationTest`, etc.) against a real,
     ephemeral Postgres container the test suite itself starts
  4. `if: github.event_name == 'push' && github.ref == 'refs/heads/main'`:
     - `docker/setup-buildx-action@v3`
     - `docker/login-action@v3` against `ghcr.io`, using
       `${{ github.actor }}` / `${{ secrets.GITHUB_TOKEN }}`
     - `docker/build-push-action@v6`: `context: service-path`, `push:
       true`, `tags: <image-name>:${{ github.sha }}` truncated to the
       short SHA (`${{ github.sha }}` sliced to 7 chars, or
       `github.sha` combined with a short-SHA step output — exact
       expression left to the plan)
- A failing `mvn test` fails the job before step 4 is reached — no
  broken build is ever pushed.

### `ci-s3.yml` / `ci-iam.yml` (thin callers)

- `on: { pull_request: { paths: ["services/s3/**"] }, push: { branches:
  [main], paths: ["services/s3/**"] } }` (iam's is identical with its
  own path).
- One job, `uses: ./.github/workflows/ci-java-service.yml`, `with:
  service-name: s3, service-path: services/s3, image-name:
  ghcr.io/aliffaizuddin/aws/s3` (iam analogous).

### `ci-helm.yml`

- `on: { pull_request: { paths: ["deploy/helm/**"] }, push: { branches:
  [main], paths: ["deploy/helm/**"] } }`.
- Steps: checkout, `azure/setup-helm@v4`, then `helm lint` and `helm
  template` against the umbrella chart and each subchart
  independently, matching the same commands used for manual validation
  in the Helm chart sub-project.
- `templates/postgres/secret.yaml` and `charts/iam/templates/secret.yaml`
  both use Helm's `required` guard (added in the Helm chart sub-project's
  fix wave) against `.Values.postgres.password` and
  `.Values.jwt.secret` — with no real `values-secrets.yaml` present in
  CI (it's gitignored, never committed), `helm lint`/`template` will
  fail those `required` checks unless dummy values are supplied. Exact
  mechanism (inline `--set postgres.password=... --set
  iam.jwt.secret=...` flags with harmless placeholder values, vs. a
  small committed `values-ci.yaml` fixture) is left to the plan — both
  satisfy the guard without weakening it, since neither is a real
  secret and neither is used outside this CI job.

## 6. Registry and tagging

- Registry: `ghcr.io`, under the repo owner's namespace
  (`ghcr.io/aliffaizuddin/aws/<service>`).
- Auth: the workflow's automatic `GITHUB_TOKEN`, via
  `docker/login-action`. No repository secret to create; GHCR grants
  push access to a repo's own `GITHUB_TOKEN` by default for that repo's
  packages.
- Tag: short git SHA only (e.g. `s3:a3f9c21`) — immutable and directly
  traceable to the exact commit on `main` that produced it. No
  floating `latest` tag, so there is never an ambiguous "which build is
  this?" image. This is also the exact shape ArgoCD's later sub-project
  will need: bump the SHA referenced in `values.yaml`, ArgoCD syncs
  that exact, already-tested build.
- `deploy/helm/cloudlite/values.yaml`'s current `image.tag: "0.1.0"`
  values are untouched by this sub-project (per §3 Non-goals) — they
  keep pointing at the locally-built dev images used for k3d
  validation until ArgoCD's sub-project wires the registry image in.

## 7. Testing

- Static: none of these workflows can be "unit tested" in isolation;
  correctness is validated by triggering them for real.
- End-to-end, against the real repo:
  - A throwaway branch with a trivial change under `services/s3/**`,
    opened as a PR: confirms `ci-s3.yml` fires, `ci-iam.yml` does not,
    `mvn test` genuinely runs (and genuinely fails if a test is broken
    on purpose as a smoke check), and no image is pushed.
  - The same PR merged to `main` (or a direct push, if a merge isn't
    warranted for a throwaway change): confirms the build+push steps
    now run and `ghcr.io/aliffaizuddin/aws/s3:<sha>` is pullable
    (`docker pull` or `gh api /orgs/.../packages` / the repo's Packages
    UI).
  - The equivalent pair of checks for `services/iam/**`.
  - A throwaway change under `deploy/helm/**`: confirms `ci-helm.yml`
    fires (and only it), and that it genuinely fails on a deliberately
    broken chart value before being fixed and confirmed green.
- All throwaway branches/PRs used for validation are cleaned up
  (branch deleted, PR closed or merged) once each check is confirmed —
  none are meant to be long-lived.

## 8. Open items for the implementation plan

- Exact expression for deriving the short SHA tag (`github.sha` sliced
  to 7 characters via a shell step, vs. `${{ github.sha }}` with
  Docker's own short-form tag support) — left to the plan; no
  behavioral difference.
- Exact mechanism for satisfying the `required()` guards in
  `ci-helm.yml` (inline `--set` flags vs. a small committed
  `values-ci.yaml` fixture with placeholder, non-secret values) — left
  to the plan, per §5.
- Whether `ci-s3.yml`/`ci-iam.yml` also trigger on other branches (not
  just `main`) for the push-side test-only path, or whether push
  events are scoped to `main` alone (with all other branches relying
  on the PR trigger for test coverage) — left to the plan; recommend
  scoping push triggers to `main` only, since every other branch's
  commits are already covered by the PR trigger once a PR is open.
