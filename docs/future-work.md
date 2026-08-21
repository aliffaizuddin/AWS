# Future Work / Explicit Non-Goals

Written before code exists, on purpose — this is the scope fence. Anything below is a
"future work" bullet point in interviews and READMEs, not something to build now.

Rationale: an AWS-style clone can expand indefinitely (replication, IAM condition
keys, multi-runtime functions...). Deciding the boundary up front — and writing it
down — is itself a signal worth having on the resume ("I scoped deliberately instead
of scope-creeping").

## S3 clone — out of scope
- Cross-region replication
- Lifecycle policies (auto-expiry / storage tiering)
- Server-side encryption
- Event notifications beyond the single "object-created → function runner" trigger

- Full AWS-style bucket-name validation (DNS-safe charset, 3-63 character
  length limits, etc.). The Phase 1 design spec
  (`docs/superpowers/specs/2026-08-13-s3-clone-phase1-design.md` §11) flagged
  this as an open item and recommended enforcing it from the start; the
  implementation currently only rejects the empty string and the reserved
  name `healthz`. Deferred, not forgotten — cheap to add later, but not
  needed for internal ad-hoc testing.

## IAM clone — out of scope
- Cross-account roles / assume-role chains
- MFA, SSO / federation
- Fine-grained condition keys (IP restrictions, time-based conditions, etc.)

## Function runner — out of scope
- Multiple runtimes (pick one guest language only — Python)
- Concurrency scaling / provisioned concurrency
- VPC-style networking for functions

## Platform / infra — out of scope for now
- Multi-node k3s with real separate physical hardware (single bare-metal node is the
  current target; revisit if a second physical machine becomes available)
- HashiCorp Vault (Sealed Secrets is the current secrets story; Vault is a legitimate
  later addition, not a v1 requirement)
- Full ELK stack (Elasticsearch/Logstash/Kibana) — resource footprint (~5-7GB
  minimum) doesn't fit the 12GB budget alongside actual workloads; see
  `architecture.md` §13 for the hardware tier that would make this viable later
- 3-node HA clustering for any stateful component (Postgres, Elasticsearch, etc.)

## Language strategy — out of scope for now

- S3 and IAM are Java (existing strength, faster system-design iteration than learning
  a language simultaneously). Function runner is Go (JVM cold-start is a poor fit for
  per-invocation execution regardless of RAM).
- Not doing now: porting S3/IAM to Go. Not needed — the SRE/platform story lives in
  the infra layer (k3s, Helm, ArgoCD, observability), which is language-agnostic.
- **Future new services default to Go**, once RAM headroom (post-upgrade or
  post-second-node) stops making the JVM tax a real constraint. This gives a natural
  "v1 constrained, v2 expanded" narrative rather than a static one-shot project.

## Revisit triggers

Reasons to actually pick one of these back up later:
- **Second physical machine acquired** → real multi-node k3s becomes worth doing
- **RAM upgrade or second node lands** → start writing new services in Go instead of Java
- **Targeting a job posting that explicitly lists ELK** → swap Loki for Filebeat +
  Elasticsearch + Kibana (see architecture.md §13 for sizing)
- **Targeting a job posting that explicitly lists Vault** → replace Sealed Secrets
- **Before wiring in the real `aws` CLI / SDKs against this service for anything
  beyond ad-hoc testing** → enforce full AWS-style bucket-name validation
  (DNS-safe charset, 3-63 chars)
