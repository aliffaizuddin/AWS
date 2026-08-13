# 2. Go for all backend services

## Status
Accepted

## Context
The S3 clone, IAM clone, and function runner all need a backend
implementation language. This project is explicitly meant to demonstrate
platform/SRE depth alongside backend depth, in an ecosystem (k8s, Docker,
Prometheus, ArgoCD, Terraform providers) that is itself written in Go.

## Decision
Write S3, IAM, and the function runner in Go.

## Consequences
- Directly matches the language of the tools being operated day-to-day,
  which reads well in an SRE/platform interview context.
- Native goroutine concurrency is a good fit for multipart upload
  handling and concurrent policy-check requests.
- Static binaries compile to small container images — matters given the
  256GB SSD budget shared with the OS, k3s, and Postgres data.

## Alternatives considered
- **Node.js/TypeScript or Python for the backend**: rejected as the
  primary backend language — doesn't match the surrounding
  infra-as-code ecosystem as directly, and static-binary image sizing
  is worse.
- **Python was kept**, but scoped down to the function runner's guest
  runtime only — see
  [0003](0003-python-function-runner-guest-language.md).
