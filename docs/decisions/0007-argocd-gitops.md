# 7. ArgoCD for GitOps

## Status
Accepted

## Context
Once the Helm chart exists, something needs to sync it into the k3s
cluster whenever the manifests change in git, matching how real
platform teams operate rather than manually running `helm upgrade`.

## Decision
Use ArgoCD to sync the Helm chart into the cluster on git commit.

## Consequences
- Git becomes the single source of truth for cluster state — the
  standard GitOps model.
- Adds a component to run and understand (ArgoCD itself), which is
  accounted for in the server capacity budget (`architecture.md` §8).
- Provisioning the k3s node itself (via Terraform/Ansible) happens
  before ArgoCD is ever installed — a one-time step, not part of the
  steady-state deploy loop (`architecture.md` §7).

## Alternatives considered
- **Manual `helm upgrade` from CI**: rejected — push-based deploys from
  CI are a materially different (and less resume-standard) model than
  pull-based GitOps reconciliation.
- **FluxCD**: comparable alternative; ArgoCD was chosen for its more
  prevalent name-recognition in platform interviews.
