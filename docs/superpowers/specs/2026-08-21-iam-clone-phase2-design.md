# IAM Clone — Phase 2 (Standalone) — Design

Date: 2026-08-21
Status: Approved, not yet planned/implemented

## 1. Context

Per `architecture.md` §11's build order, S3 clone Phase 1 (foundation)
is complete and merged (PR #7). This is build-order step 2: the IAM
clone, standalone — no wiring into S3 yet (that's step 3, a separate
future brainstorm/spec). `docs/services/iam.md` already scopes this
service at a high level: users/roles with `Effect`/`Action`/`Resource`
policy documents, a deny-overrides-allow policy evaluation engine, and
API key/JWT auth for service-to-service calls, backed by the same
Postgres instance S3 uses. `docs/future-work.md` explicitly excludes
cross-account roles/assume-role chains, MFA/SSO/federation, and
fine-grained condition keys from this service, at any phase.

This document specs the concrete shape of that standalone build: the
data model, the auth flow, the policy engine's semantics, and the API
surface — none of which `iam.md` pins down at the implementation
level.

## 2. Goals (Phase 2)

- Stand up `services/iam/` as a working Spring Boot service with real
  user/role/policy CRUD, backed by Postgres.
- Implement the deny-overrides-allow policy evaluation engine as
  pure, framework-free logic, unit-tested in isolation — per
  `architecture.md` §11's explicit instruction to test the engine
  before S3 is wired in as a consumer.
- Implement the auth flow end-to-end: API key issuance at user
  creation, `/auth/token` exchange for a short-lived signed JWT,
  and a JWT-gated `/authorize` endpoint that runs the policy engine
  against a caller-supplied `(action, resource)` pair. This is the
  exact surface S3 will call in step 3 via a future `iamclient`
  package — building it now, even with no real caller yet, is what
  "standalone" means here.
- Follow the same repo/package conventions as `services/s3/`
  (`controller/`, `service/`, `repository/`, `domain/`, `error/`) and
  the same tech stack (Java 21, Spring Boot 3.3.4, Maven, Spring MVC +
  virtual threads, Spring Data JPA/Hibernate, Flyway).
- Expose `/healthz` from the first commit, per `architecture.md` §11.

## 3. Non-goals (Phase 2)

- Wiring IAM into S3 (`iamclient`, S3-side call sites) — build-order
  step 3, a separate future spec.
- Cross-account roles / assume-role chains, MFA, SSO/federation,
  fine-grained condition keys (IP/time-based conditions) — see
  `docs/future-work.md`; permanently out of scope for this service,
  not just deferred.
- Auth on IAM's own admin endpoints (create user/role/policy, attach).
  They stay open in this phase, mirroring S3 Phase 1's own bootstrap
  posture: nothing yet exists to authenticate the admin caller, and
  IAM is the thing that would provide that authentication. Locking
  these down is a natural follow-up once a real caller (e.g. the web
  console) exists to authenticate as.
- AWS wire-compatibility. Unlike S3 (which must speak the real S3 REST
  wire format), nothing consumes IAM via an AWS SDK/CLI — plain JSON
  REST is the API style here.
- Token refresh flows, revocation lists, or key rotation for the JWT
  signing secret — a single static HMAC secret from config is enough
  for a standalone phase with no real callers yet.

## 4. Architecture

A single Spring Boot application (`services/iam`), plain JSON REST,
Spring MVC + virtual threads (`spring.threads.virtual.enabled=true`),
same rationale as S3: blocking controllers, virtual threads absorb
concurrent-request cost, no reactive programming model needed.

```
Admin caller (curl / future web console)
        │
        ▼
services/iam (Spring Boot, Spring MVC + virtual threads)
        │
        ├── controller/ ─▶ service/ ─▶ repository/ ──▶ Postgres (JPA/Hibernate)
        │                       │
        │                       └──▶ policy/ (pure evaluation engine, no Spring deps)
        │
        └── AuthController: API key → JWT (jjwt, HMAC-SHA256)
```

No S3 calls into IAM yet, and no IAM calls out anywhere — this phase
is fully self-contained. The `/authorize` endpoint exists and is fully
implemented/tested now so that step 3's `iamclient` has a stable,
already-proven contract to call against.

## 5. Components

### `domain/`
- `User` (id, username, `apiKeyHash`, `createdAt`).
- `Role` (id, name, `createdAt`).
- `Policy` (id, name, `document` — JSON column, see §6 for shape).
- `UserRole` (join: `userId`, `roleId`) — many-to-many membership, no
  assumption semantics (static, not session-scoped).
- `UserPolicy` (join: `userId`, `policyId`) — direct attachment.
- `RolePolicy` (join: `roleId`, `policyId`) — attachment to a role's
  bundle.

### `policy/` — the evaluation engine
- Framework-free: takes a `List<PolicyStatement>` (the caller's
  *effective* policy set, already resolved) plus an `(action,
  resource)` pair, returns `Decision.ALLOW` or `Decision.DENY`.
- `PolicyStatement`: `effect` (`ALLOW`/`DENY`), `actions` (list of
  strings, each an exact string or ending in a single trailing `*`
  wildcard, e.g. `s3:*`), `resources` (list of ARN strings, same
  wildcard rule, e.g. `arn:cloudlite:s3:::my-bucket/*`).
- Evaluation rule (deny-overrides-allow, AWS's own semantics): collect
  every statement whose `actions` and `resources` both match the
  request; if any matching statement is `DENY`, the decision is
  `DENY`; else if at least one matching statement is `ALLOW`, the
  decision is `ALLOW`; else (nothing matched) the decision is `DENY`
  (implicit default-deny).
- Matching: an `actions`/`resources` entry matches the request value
  either as an exact string match, or — if the entry ends in `*` — as
  a prefix match on everything before the `*`.

### `repository/`
- Spring Data JPA repositories for `User`, `Role`, `Policy`,
  `UserRole`, `UserPolicy`, `RolePolicy`.
- `AuthorizationService` (in `service/`, not `repository/`) resolves a
  user's effective `List<PolicyStatement>` by: loading the user's
  direct policies, loading all roles the user is a member of, loading
  each of those roles' policies, unioning the parsed statement lists,
  and handing the result to the `policy/` engine.

### `service/`
- `UserService` — create (generates a random API key, stores only its
  hash — e.g. SHA-256 — never the raw key after the creation
  response), get, list.
- `RoleService` / `PolicyService` — CRUD + attachment operations
  (user↔role, user↔policy, role↔policy).
- `AuthService` — verifies a presented raw API key against the stored
  hash, and on success issues a signed JWT via jjwt: `sub` = user id,
  `iat`/`exp` claims (15 minute expiry), HMAC-SHA256 signed with a
  secret from config (`iam.jwt.secret`).
- `AuthorizationService` — verifies a presented JWT's signature and
  expiry, extracts `sub`, resolves the effective policy set (see
  above), and calls the `policy/` engine for the requested
  `(action, resource)`.
- All throw a shared `IamApiException(code, httpStatus, message)` for
  domain errors; a `@RestControllerAdvice` renders it as JSON (§8).

### `controller/`
- `UserController`, `RoleController`, `PolicyController` — thin admin
  CRUD, same binding-only discipline as S3's controllers (business
  rules live in `service/`).
- `AuthController` — `POST /auth/token`.
- `AuthorizationController` — `POST /authorize`.
- `HealthController` — `GET /healthz`.

### Persistence — schema

Flyway migrations (`src/main/resources/db/migration/`):

```sql
-- V1__create_users.sql
CREATE TABLE users (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username     TEXT NOT NULL UNIQUE,
    api_key_hash TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- V2__create_roles.sql
CREATE TABLE roles (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- V3__create_policies.sql
CREATE TABLE policies (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT NOT NULL UNIQUE,
    document   JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- V4__create_attachments.sql
CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id),
    role_id UUID NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE user_policies (
    user_id   UUID NOT NULL REFERENCES users(id),
    policy_id UUID NOT NULL REFERENCES policies(id),
    PRIMARY KEY (user_id, policy_id)
);

CREATE TABLE role_policies (
    role_id   UUID NOT NULL REFERENCES roles(id),
    policy_id UUID NOT NULL REFERENCES policies(id),
    PRIMARY KEY (role_id, policy_id)
);
```

A `policies.document` value looks like:

```json
{
  "statements": [
    {
      "effect": "ALLOW",
      "actions": ["s3:GetObject", "s3:ListBucket"],
      "resources": ["arn:cloudlite:s3:::my-bucket", "arn:cloudlite:s3:::my-bucket/*"]
    },
    {
      "effect": "DENY",
      "actions": ["s3:DeleteObject"],
      "resources": ["arn:cloudlite:s3:::my-bucket/*"]
    }
  ]
}
```

## 6. API surface (Phase 2)

| Operation | Method + Path | Success | Key error cases |
|---|---|---|---|
| CreateUser | `POST /users` | 201, `{id, username, apiKey}` (raw key shown once) | 409 `USER_ALREADY_EXISTS` |
| ListUsers | `GET /users` | 200, `[{id, username, createdAt}]` | — |
| GetUser | `GET /users/{id}` | 200 | 404 `USER_NOT_FOUND` |
| CreateRole | `POST /roles` | 201 | 409 `ROLE_ALREADY_EXISTS` |
| ListRoles / GetRole | `GET /roles`, `GET /roles/{id}` | 200 | 404 `ROLE_NOT_FOUND` |
| CreatePolicy | `POST /policies` (body: `{name, document}`) | 201 | 400 `INVALID_ARGUMENT` (malformed document) |
| ListPolicies / GetPolicy | `GET /policies`, `GET /policies/{id}` | 200 | 404 `POLICY_NOT_FOUND` |
| AttachUserRole | `POST /users/{id}/roles/{roleId}` | 204 | 404 `USER_NOT_FOUND`/`ROLE_NOT_FOUND` |
| AttachUserPolicy | `POST /users/{id}/policies/{policyId}` | 204 | 404 `USER_NOT_FOUND`/`POLICY_NOT_FOUND` |
| AttachRolePolicy | `POST /roles/{id}/policies/{policyId}` | 204 | 404 `ROLE_NOT_FOUND`/`POLICY_NOT_FOUND` |
| IssueToken | `POST /auth/token` (header: `Authorization: ApiKey <key>`) | 200, `{token, expiresIn}` | 401 `INVALID_API_KEY` |
| Authorize | `POST /authorize` (header: `Authorization: Bearer <jwt>`; body: `{action, resource}`) | 200, `{decision: "ALLOW"|"DENY"}` | 401 `TOKEN_EXPIRED`/`TOKEN_INVALID`; 400 `INVALID_ARGUMENT` |
| Health | `GET /healthz` | 200 if DB reachable | 503 otherwise |

## 7. Error handling

JSON error body on every 4xx/5xx, rendered by a
`@RestControllerAdvice`:

```json
{
  "code": "USER_NOT_FOUND",
  "message": "No user with id 3f9c2e10-...-b1a2"
}
```

`IamErrorCode` enum with a fixed status mapping: `USER_NOT_FOUND` /
`ROLE_NOT_FOUND` / `POLICY_NOT_FOUND` → 404; `USER_ALREADY_EXISTS` /
`ROLE_ALREADY_EXISTS` → 409; `INVALID_API_KEY` / `TOKEN_EXPIRED` /
`TOKEN_INVALID` → 401; `INVALID_ARGUMENT` → 400; unmatched route → 404
`NOT_FOUND`; unhandled exception → 500 `INTERNAL_ERROR` (generic
message; real exception logged server-side only, matching S3's
non-leaky-error rule from its own final review). Spring's own 4xx
exceptions (malformed JSON body, unsupported media type) get explicit
handlers mapping to `INVALID_ARGUMENT`/400 rather than falling through
to the generic 500 handler — same fix class S3 needed in its final
review, applied here from the start instead of discovered later.

## 8. Testing

- **Policy engine unit tests** (JUnit 5, no Spring context): explicit
  allow, explicit deny, deny-overrides-allow on conflicting
  statements, wildcard action match, wildcard resource match,
  no-statement-matches → implicit deny. This is the suite
  `architecture.md` §11 calls out as needing to pass before S3 is
  wired in as a consumer.
- **Service/repository tests** (JUnit 5 + Mockito for services,
  `@DataJpaTest` for repositories) — same per-layer split as S3.
- **Controller tests** (`@WebMvcTest` + `MockMvc`) against mocked
  services.
- **Integration test** (Testcontainers Postgres + `@SpringBootTest`):
  real end-to-end flow — create user, create policy (one allow
  statement scoped to one resource), attach policy to user,
  `POST /auth/token` with the returned API key, `POST /authorize` with
  a matching resource (expect `ALLOW`) and a non-matching resource
  (expect `DENY`).
- Manual/exploratory verification via `docker-compose up` + curl,
  same as S3.

## 9. Local dev / deployment

- `services/iam/Dockerfile`: same multi-stage shape as S3's
  (`maven:3.9-eclipse-temurin-21` build → `eclipse-temurin:21-jre`
  runtime).
- Add an `iam` service to the root `docker-compose.yml`, pointed at
  the same Postgres container S3 uses (per `iam.md`'s "same instance
  as S3's metadata index"), its own `SPRING_DATASOURCE_URL` (own
  logical database or schema — left to the plan), `JAVA_TOOL_OPTIONS:
  "-Xmx768m"` (matching `architecture.md` §8's 768Mi allocation for
  the IAM pod).
- Config: `application.yml` + env var overrides, `iam.jwt.secret`
  sourced from an env var (never checked into `application.yml` with
  a real value — a dev-only default is fine for local
  `docker-compose`).
- `docs/services/iam.md` updated to describe what was actually built
  (currently a "not yet built" placeholder), pointing at this spec and
  its implementation plan.

## 10. Open items for the implementation plan

- Whether `Policy.document`'s JSON (de)serialization uses Jackson
  directly against a `PolicyDocument`/`PolicyStatement` record pair,
  or is stored/read as a raw `JsonNode` — implementation detail, no
  behavioral difference; left to the plan.
- Exact API-key generation scheme (length, charset, encoding) and hash
  algorithm (SHA-256 is assumed above) — left to the plan.
- Whether `iam.jwt.secret` needs a minimum-length validation at
  startup (a too-short HMAC secret is a real weak-signing risk) — left
  to the plan; recommend enforcing at least 32 bytes.
