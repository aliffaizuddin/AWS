# 6. Helm umbrella chart for deployment manifests

## Status
Accepted

## Context
Every service (S3, IAM, fnrunner, web) plus Postgres needs Kubernetes
manifests, and dev vs. prod will need different values (replica counts,
PVC sizes) without forking the whole manifest set per environment.

## Decision
Use Helm, structured as one umbrella chart (`cloudlite`) with a
per-service subchart under `charts/`, plus a `values-dev.yaml` overlay.

## Consequences
- `helm install cloudlite` brings up the whole platform in one command;
  each subchart is still independently testable via
  `helm template charts/<service>`.
- More resume-standard than raw YAML manifests, and solves the real
  dev/prod values-override problem instead of hand-rolling it.
- Requires establishing a values-key naming convention early (e.g.
  `s3.persistence.size`, `iam.replicaCount`) before it gets messy
  across subcharts — see `architecture.md` §10.

## Alternatives considered
- **Raw Kubernetes YAML manifests**: rejected — no clean story for
  dev/prod value overrides without duplicating manifests or building an
  ad hoc templating layer.
- **Kustomize**: viable alternative, but Helm is more broadly the
  industry-standard expectation in platform/SRE interview contexts.
