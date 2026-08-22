# IAM clone

**Status:** Phase 2 (standalone) built — user/role/policy CRUD, a
deny-overrides-allow policy evaluation engine, and an API-key-to-JWT
auth flow with a JWT-gated `/authorize` decision endpoint. Not yet
wired into S3 — that's a later build-order step (`architecture.md`
§11, step 3) with its own future spec/plan. See
[`../superpowers/plans/2026-08-21-iam-clone-phase2.md`](../superpowers/plans/2026-08-21-iam-clone-phase2.md)
for what was built and
[`../superpowers/specs/2026-08-21-iam-clone-phase2-design.md`](../superpowers/specs/2026-08-21-iam-clone-phase2-design.md)
for the design.

## Scope

- Users, roles, and policies with attached JSON policy documents
  (`Effect`/`Action`/`Resource`-shaped statements)
- Roles are policy bundles: a user can be a member of zero or more
  roles (static membership) and/or have policies attached directly —
  no assume-role/session semantics
- Policy evaluation engine (`policy/` package, framework-free,
  unit-tested in isolation) — deny-overrides-allow, exact or
  trailing-`*`-wildcard match on actions/resources, implicit
  default-deny
- Auth: `POST /auth/token` exchanges a user's API key for a
  short-lived signed JWT; `POST /authorize` verifies that JWT and runs
  the policy engine against a caller-supplied `(action, resource)`
  pair — this is the exact surface S3 will call once wired in

## Tech stack

- Java 21, Spring Boot (Spring MVC + virtual threads), Maven
- Spring Data JPA + Hibernate over PostgreSQL, Flyway migrations
- jjwt for JWT signing/verification (HMAC-SHA256)
- Plain JSON REST — no AWS wire-compatibility requirement (unlike S3)

## Storage

- Users/roles/policies: PostgreSQL (see
  [`../decisions/0005-postgresql-database.md`](../decisions/0005-postgresql-database.md)),
  same instance as S3's metadata index — table names don't collide
- Policy documents stored as `jsonb`, mapped via Hibernate's native
  JSON column support (no extra Hibernate-types dependency)

## Dependencies

- None yet. Will be consumed by the S3 service via a dedicated
  `iamclient` package once wired in (see [`s3.md`](s3.md)) —
  `architecture.md` §11, step 3.

## Build/test notes

Per `architecture.md` §11: the policy engine (deny-overrides-allow
logic) is unit-tested in isolation, with zero Spring/Jakarta
dependencies, ahead of any real caller existing. `/healthz` is
exposed from the first commit. Admin CRUD endpoints (`/users`,
`/roles`, `/policies`, and their attachment endpoints) are open — no
auth — in this phase, mirroring S3 Phase 1's own bootstrap posture;
only `/auth/token` and `/authorize` are auth-gated.

## Out of scope

See `../future-work.md` — cross-account roles/assume-role chains,
MFA, SSO/federation, and fine-grained condition keys (IP
restrictions, time-based conditions) are explicitly not part of this
service, at any phase.
