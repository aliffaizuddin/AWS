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

## Revisit triggers

Reasons to actually pick one of these back up later:
- **Second physical machine acquired** → real multi-node k3s becomes worth doing
- **Targeting a job posting that explicitly lists ELK** → swap Loki for Filebeat +
  Elasticsearch + Kibana (see architecture.md §13 for sizing)
- **Targeting a job posting that explicitly lists Vault** → replace Sealed Secrets
