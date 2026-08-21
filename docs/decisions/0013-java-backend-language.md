# 13. Java for S3 and IAM backend services

## Status
Accepted

Supersedes [0002](0002-go-backend-language.md) for S3 and IAM specifically —
the function runner remains Go, unchanged from 0002.

## Context
The S3 clone and IAM clone both need a backend implementation language. This
project is explicitly meant to demonstrate backend engineering depth
alongside platform/SRE depth, and the actual system-design work on these two
services — multipart-upload correctness, a policy evaluation engine — is
where that backend signal lives. 0002 originally picked Go for all three
backend services (S3, IAM, function runner) to match the surrounding
infra-as-code ecosystem. In practice, learning Go and building that
system-design work simultaneously was slower than necessary.

## Decision
Write S3 and IAM in Java (Spring Boot, targeting Java 21+ virtual threads).
The function runner stays Go — see 0002's original rationale (JVM cold-start
cost is a poor fit for a per-invocation, Lambda-style executor), which is
unaffected by this decision.

## Consequences
- Existing strength in Java means more time spent on the actual
  system-design work (multipart-upload correctness, policy engine) instead
  of learning a new language at the same time.
- Java 21 virtual threads give a modern answer to Go's goroutine-style
  concurrency for many concurrent upload/policy-check requests, so the
  concurrency rationale behind 0002 is preserved even though the language
  changed.
- The project becomes intentionally polyglot (Java for S3/IAM, Go for the
  function runner, Python for the function runner's guest runtime) rather
  than a single-language stack. This is a legitimate, documented trade-off,
  not an inconsistency — see `docs/future-work.md`'s "Language strategy"
  section.
- Slightly larger container images and slower cold start than the Go
  baseline in 0002; acceptable for S3/IAM, which run as long-lived pods
  rather than per-invocation like the function runner.
- Future new services default back to Go once RAM headroom allows (see
  `architecture.md` §3 and `docs/future-work.md`'s revisit triggers) — this
  decision is scoped to S3 and IAM as they exist today, not a permanent
  reversal of 0002's ecosystem-alignment reasoning.

## Alternatives considered
- **Staying with Go for S3/IAM** (i.e. not superseding 0002): rejected for
  this phase — the ecosystem-alignment argument in 0002 is still valid in
  the abstract, but it was outweighed by the velocity cost of learning Go
  and doing new system-design work at the same time.
- **Porting S3/IAM to Go later**: considered and explicitly deferred, not
  planned — see `docs/future-work.md`'s "Language strategy — out of scope
  for now" section. The SRE/platform story lives in the infra layer (k3s,
  Helm, ArgoCD, observability), which is language-agnostic, so there's no
  strong pull to revisit this beyond the documented revisit triggers.
