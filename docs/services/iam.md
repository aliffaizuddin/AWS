# IAM clone

**Status:** not yet built. Build second, per `architecture.md` §11 —
standalone and unit-tested before S3 calls into it.

## Scope

- Users/roles with attached JSON policy documents
  (`Effect`/`Action`/`Resource` shape)
- Policy evaluation engine — deny-overrides-allow logic
- API key / JWT auth for service-to-service calls

## Storage

- Users/roles/policy documents: PostgreSQL (see
  [`../decisions/0005-postgresql-database.md`](../decisions/0005-postgresql-database.md)),
  same instance as S3's metadata index

## Dependencies

- Consumed by the S3 service via `internal/iamclient` (see
  [`s3.md`](s3.md)) — every S3 request goes through policy evaluation
  once wired in.

## Build/test notes

Per `architecture.md` §11: the policy engine (deny-overrides-allow
logic) should be unit-tested in isolation before S3 is wired in as a
consumer. Expose `/healthz` from the first commit.

## Out of scope

See `../future-work.md` — cross-account roles/assume-role chains, MFA,
SSO/federation, and fine-grained condition keys (IP restrictions,
time-based conditions) are explicitly not part of this service.
