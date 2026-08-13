# 11. Monorepo

## Status
Accepted

## Context
CloudLite is made of multiple independently-deployable services (S3,
IAM, fnrunner, web) plus deployment tooling (Terraform, Helm, ArgoCD
application manifests). These could live in one repo or be split one
repo per service/tool.

## Decision
Keep everything in a single repository, with services separated under
`services/<name>/` and deployment config under `deploy/`.

## Consequences
- One coherent system to walk through in an interview — no jumping
  between repos to show how S3, IAM, and the platform layer fit
  together.
- Simpler CI wiring: path-triggered workflows
  ([0008](0008-github-actions-cicd.md)) give per-service build
  isolation without needing separate repos to get it.
- Requires discipline to keep service boundaries clean at the code
  level even though they're not enforced by repo boundaries (e.g. no
  service reaching directly into another's internal package).

## Alternatives considered
- **One repo per service**: rejected — adds cross-repo versioning and
  release coordination overhead that this project's scope doesn't need,
  and makes the "one system, two interview narratives" pitch
  (`architecture.md` §12) harder to demo from a single checkout.
