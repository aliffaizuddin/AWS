# CI/CD

**Status:** built — path-triggered GitHub Actions workflows for
`services/s3` and `services/iam`, plus a Helm lint/template workflow
for `deploy/helm/cloudlite`. See
[`../superpowers/plans/2026-08-24-cicd.md`](../superpowers/plans/2026-08-24-cicd.md)
for what was built and
[`../superpowers/specs/2026-08-24-cicd-design.md`](../superpowers/specs/2026-08-24-cicd-design.md)
for the design.

## Scope

- `.github/workflows/ci-java-service.yml` — reusable `workflow_call`
  workflow: checkout, JDK 21 (Temurin) setup with Maven caching,
  `mvn -B test`, and (only on a push to `main`) build the service's
  existing `Dockerfile` and push it to `ghcr.io`, tagged with the
  short git SHA.
- `.github/workflows/ci-s3.yml` / `ci-iam.yml` — thin, path-triggered
  callers into the reusable workflow (`services/s3/**` /
  `services/iam/**`).
- `.github/workflows/ci-helm.yml` — path-triggered on
  `deploy/helm/**`: `helm lint` + `helm template` against the umbrella
  chart and both subcharts independently. Uses two small committed
  fixtures (`deploy/helm/cloudlite/values-ci.yaml`,
  `deploy/helm/cloudlite/charts/iam/values-ci.yaml`) to satisfy the
  chart's `required()` secret guards — these are placeholder,
  non-secret values, safe to commit, and unrelated to the real
  (gitignored) `values-secrets.yaml` used for actual installs.

## Registry and tagging

Images publish to `ghcr.io/aliffaizuddin/aws/<service>`, tagged with
the 7-character short git SHA of the commit on `main` that produced
them (e.g. `s3:a3f9c21`) — no floating `latest` tag. Authentication
uses the workflow's built-in `GITHUB_TOKEN`; no registry secret exists
in this repo.

## Trigger behavior

| Event | s3/iam workflows | helm workflow |
|---|---|---|
| PR touching `services/s3/**` or `services/iam/**` | `mvn test` only, no image push | doesn't fire |
| PR touching `deploy/helm/**` | doesn't fire | `helm lint`/`template` only |
| Push to `main` touching `services/s3/**` or `services/iam/**` | `mvn test`, then build+push | doesn't fire |
| Push to `main` touching `deploy/helm/**` | doesn't fire | `helm lint`/`template` only |

## Validated against

The real GitHub repository, not a sandbox stand-in — see this plan's
Task 6 for the exact validation branches/PRs used to confirm each
workflow fires only for its own path, that `mvn test` genuinely runs
each service's Testcontainers-backed integration suite, and that
`ci-helm.yml` genuinely fails on a broken chart value before passing
once fixed.

## Out of scope

`ci-fnrunner.yml`/`ci-web.yml` (neither service exists yet),
auto-bumping `deploy/helm/cloudlite/values.yaml`'s image tags (the
ArgoCD sub-project's job), any registry other than `ghcr.io`, image
signing/provenance attestation, and configuring GitHub branch
protection "required checks" (a one-time repo-settings action, not a
file in this repo).
