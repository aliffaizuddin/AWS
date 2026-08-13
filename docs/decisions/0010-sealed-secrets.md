# 10. Sealed Secrets for secrets management

## Status
Accepted

## Context
Database credentials, JWT signing keys, and other secrets need to be
committed to the GitOps repo (so ArgoCD can sync them) without landing
in plaintext in version control.

## Decision
Use Sealed Secrets to encrypt secrets so they're safe to commit, with
the controller decrypting them in-cluster.

## Consequences
- Secrets can live in the same git-synced GitOps flow as everything else
  ([0007](0007-argocd-gitops.md)) without a plaintext-in-git problem.
- Lower operational overhead than running a standalone secrets-manager
  service — no extra component to deploy, monitor, or unseal after a
  restart.
- Not a long-term enterprise secrets story (no dynamic secrets, leasing,
  or audit trail beyond git history) — acceptable for this project's
  scope.

## Alternatives considered
- **HashiCorp Vault**: legitimate future addition, explicitly deferred
  — see `future-work.md`. Vault adds real operational weight (its own
  HA/unsealing story) that isn't justified until there's a concrete
  reason (e.g. a target job posting that lists it).
- **Plaintext values files with `.gitignore`**: rejected — fragile
  (one mistake commits a secret) and doesn't compose with GitOps, where
  the whole point is that git is authoritative.
