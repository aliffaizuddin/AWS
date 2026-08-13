# 8. GitHub Actions, path-triggered per service

## Status
Accepted

## Context
The repo is a monorepo (see [0011](0011-monorepo-structure.md))
containing multiple independently-deployable services. A naive CI setup
would rebuild and redeploy every service on every commit, regardless of
which service's code actually changed.

## Decision
Use GitHub Actions with one workflow per service
(`ci-s3.yml`, `ci-iam.yml`, `ci-fnrunner.yml`, `ci-web.yml`), each
path-triggered to only its own service directory.

## Consequences
- A change to `services/s3/` only builds/tests/publishes the S3 image;
  IAM and fnrunner pipelines don't run.
- Keeps CI runtime and image-registry churn proportional to what
  actually changed, which matters more as the monorepo grows.

## Alternatives considered
- **Single monolithic CI workflow for the whole repo**: rejected —
  wastes CI time rebuilding unrelated services and defeats the purpose
  of splitting services into independently-deployable units.
- **Separate repos per service**: rejected in favor of a monorepo —
  see [0011](0011-monorepo-structure.md) for that rationale.
