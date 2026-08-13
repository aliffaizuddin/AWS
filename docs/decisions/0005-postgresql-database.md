# 5. PostgreSQL for metadata

## Status
Accepted

## Context
S3's object/bucket index and IAM's users/roles/policy documents both
need durable, queryable storage, separate from the actual object bytes
(which live on disk — see `docs/services/s3.md`).

## Decision
Use PostgreSQL as the single metadata database backing both the S3
clone's object/bucket index and IAM's user/role/policy store.

## Consequences
- One database technology to operate (backup, monitor, tune) instead
  of two, which keeps the platform-layer surface area bounded.
- Runs on the `fast-ssd` storage class — see `architecture.md` §4 for
  the SSD/HDD split rationale.
- Relational modeling fits policy documents (users/roles/policies with
  foreign keys) and object metadata (bucket → object → version) well
  without needing a document store.

## Alternatives considered
- **Separate databases per service** (e.g. Postgres for IAM, something
  else for S3 metadata): rejected — doubles operational surface for no
  clear benefit at this scale.
- **A document/NoSQL store for object metadata**: rejected — the
  metadata is fundamentally relational (versioning, bucket ownership,
  policy attachment), and one Postgres instance already fits the
  resource budget.
