# CloudLite

Self-hosted, Kubernetes-native clone of a small slice of AWS (S3 + IAM +
Lambda-style functions), built to demonstrate both backend and
platform/SRE engineering depth from a single codebase. Runs on a single
bare-metal k3s node.

## Start here

- [`docs/architecture.md`](docs/architecture.md) — full architecture
  and decision reference. Read this first.
- [`docs/decisions/`](docs/decisions/) — one ADR per major decision
  (why Go, why bare-metal k3s, why PLG over ELK, etc.) — the detailed
  rationale behind `architecture.md` §3's summary table.
- [`docs/services/`](docs/services/) — one file per service (`s3.md`,
  `iam.md`, `fnrunner.md`, `web.md`) with that service's scope and
  status. **Check the relevant file before touching a service.**
- [`docs/platform/`](docs/platform/) — platform-layer sub-projects
  (Helm charts, CI/CD, ArgoCD, observability, etc.) have their own docs
  here, one file per sub-project (e.g. `helm-charts.md`).
- [`docs/future-work.md`](docs/future-work.md) — explicit scope fence.
  If something looks missing, check here before adding it — it may be
  a deliberate non-goal with a documented revisit trigger.

## Build order

Per `architecture.md` §11:

1. S3 clone standalone (no auth)
2. IAM clone standalone
3. Wire IAM into S3 (policy evaluation on every S3 call)
4. Platform layer: k3s + Helm + ArgoCD + CI/CD + Prometheus/Grafana +
   chaos test
5. (Stretch) Function runner triggered by S3 events

## Conventions

- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/)
  — `<type>(optional scope): <description>`, types `feat|fix|docs|refactor|perf|test|build|ci|chore`.
- **Branches:** `<type>/<short-kebab-description>`, same type
  vocabulary as commits.
- Full rationale: [`docs/decisions/0012-commit-and-branch-conventions.md`](docs/decisions/0012-commit-and-branch-conventions.md).

## AI-assisted session artifacts

Design docs and implementation plans produced by AI-assisted sessions
(e.g. the superpowers brainstorming/writing-plans skills) live in
[`docs/superpowers/specs/`](docs/superpowers/specs/) and
[`docs/superpowers/plans/`](docs/superpowers/plans/) respectively —
not scattered at the repo root.
