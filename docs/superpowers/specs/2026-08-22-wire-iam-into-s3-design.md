# Wire IAM into S3 — Design

Date: 2026-08-22
Status: Approved, not yet planned/implemented

## 1. Context

Per `architecture.md` §11's build order, both `services/s3` (Phase 1
foundation, PR #7) and `services/iam` (Phase 2 standalone, PR #9) are
complete, merged to `main`, and standalone — S3 has no auth at all yet
(deliberately, per its own Phase 1 non-goals); IAM has a working
`POST /auth/token` (API key → short-lived JWT) and `POST /authorize`
(JWT + `{action, resource}` → `ALLOW`/`DENY`) surface, already built
and tested against exactly this contract.

This is build-order step 3: wiring the two together, so "every S3
call now goes through policy evaluation" (`architecture.md` §5, §11).
`docs/services/s3.md`'s "Dependencies" section already documents the
intended shape — S3 calls out to IAM on every request via a dedicated
`iamclient` package — this document specs that integration concretely.

## 2. Goals

- Every S3 endpoint (bucket and object CRUD) requires a valid,
  IAM-issued JWT and an `ALLOW` decision from IAM's `/authorize`
  before the existing controller logic runs.
- `/healthz` remains unauthenticated (health-check convention; no
  change to its existing behavior).
- Zero changes to `service/`, `repository/`, or `storage/` — the new
  auth concern lives entirely in a new HTTP-boundary layer in front of
  the existing controllers.
- Zero changes to any of the 55 existing tests' *method bodies* — the
  three existing `@WebMvcTest` classes get one `excludeFilters`
  annotation attribute each (see §7), nothing else.
- Fail-closed: if IAM is unreachable, times out, or errors, the
  request is rejected, not allowed through. This is deliberate — see
  §6 — and is the documented interview narrative in `architecture.md`
  §12 ("here's what happens when the IAM service goes down and S3
  starts failing auth checks").
- S3's existing AWS-shaped XML error contract is unchanged — new auth
  failures render through the *same* `GlobalExceptionHandler` as every
  other S3 error, not IAM's JSON shape.

## 3. Non-goals

- Any change to `services/iam` itself — this phase only adds the
  caller side. IAM's `/auth/token`/`/authorize` contract is consumed
  as-is.
- Caching `/authorize` decisions in S3. Every request round-trips to
  IAM, per `architecture.md`'s explicit "every S3 call now goes
  through policy evaluation." A decision cache is a legitimate future
  optimization, out of scope here.
- Token refresh/retry logic in S3. An expired caller JWT is a 403; the
  caller re-fetches a new token from IAM themselves.
- Byte-range GET, versioning, multipart upload, custom tags — still
  out of scope per S3's own Phase 1 non-goals; unaffected by this
  change.
- Cross-account roles, MFA/SSO, fine-grained condition keys — still
  permanently out of scope per `docs/future-work.md`.

## 4. Architecture

A new `dev.cloudlite.s3.iamclient` package, plus one `AuthInterceptor`
(a `HandlerInterceptor`, registered via a `WebMvcConfigurer`,
excluding `/healthz`) sitting in front of `BucketController` and
`ObjectController`.

```
Client (holds a JWT from IAM's own /auth/token)
        │  Authorization: Bearer <jwt>
        ▼
services/s3 (Spring MVC)
        │
        ├── AuthInterceptor.preHandle()
        │       ├── extract bucket/key from URI template vars
        │       ├── map method+path → S3 action string + ARN resource (§5)
        │       └── iamclient.authorize(bearerToken, action, resource)
        │               │
        │               ▼
        │       services/iam  POST /authorize
        │               (Bearer <jwt> forwarded verbatim, body {action, resource})
        │
        ├── ALLOW → chain continues → BucketController/ObjectController (unchanged)
        └── DENY / no token / IAM unreachable → short-circuit, AWS-shaped XML error (§6), controller never runs
```

The caller's JWT is never inspected or decoded by S3 itself — S3 has
no need to know the caller's identity, only whether IAM allows the
requested action. The same `Authorization` header value the caller
sent to S3 is forwarded to IAM's `/authorize` verbatim.

## 5. Components

### `iamclient/` (new package)
- `IamClient` — a `@Component` wrapping a Spring `RestClient`
  (Spring Framework 6.1+/Boot 3.2+'s modern synchronous HTTP client;
  fits S3's existing blocking Spring MVC + virtual-threads model with
  no reactive bridging). One method:
  `authorize(String bearerToken, String action, String resource): void`
  — returns normally on `ALLOW`, throws on everything else.
- `IamAccessDeniedException` (unchecked) — thrown when IAM returns
  `DENY`, or when IAM itself returns 401 (an invalid/expired JWT,
  surfaced by IAM's own `/authorize` as `TOKEN_EXPIRED`/`TOKEN_INVALID`).
- `IamUnavailableException` (unchecked) — thrown on connection
  failure, timeout, or any IAM 5xx response.
- `AuthorizeRequestBody`/`AuthorizeResponseBody` — small internal
  records mirroring IAM's `AuthorizeRequest`/`AuthorizeResponse` JSON
  shape (`{action, resource}` / `{decision}`) — S3's own copy, not a
  shared dependency between the two services' codebases.
- Config: `iam.base-url` (env-overridable via `IAM_BASE_URL`), 2s
  connect / 3s read timeout on the underlying `RestClient`.

### `error/` (extended)
- New `S3ErrorCode.ACCESS_DENIED` value (`"AccessDenied"`, 403) —
  AWS's own generic code for "you may not do this," deliberately
  covering missing token, malformed token, and explicit `DENY` without
  distinguishing which (see §6 for the reasoning).
- `GlobalExceptionHandler` gets one more `@ExceptionHandler`:
  `IamAccessDeniedException` → `ACCESS_DENIED`/403.
  `IamUnavailableException` is NOT separately handled here — it's
  allowed to propagate to the existing generic `Exception` handler,
  which already renders the existing `INTERNAL_ERROR`/500 (no new
  handler needed; this is the natural, already-correct behavior).

### New `AuthInterceptor` + `AuthWebMvcConfigurer`
- `AuthInterceptor implements HandlerInterceptor`: in `preHandle`,
  reads the `Authorization` header (missing/malformed → throw
  `IamAccessDeniedException` directly, short-circuiting before ever
  calling `iamclient`), resolves the request's bucket/key from
  `HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE`, maps
  method+path to an action/resource pair per §5's table below, and
  calls `iamClient.authorize(...)`. Lets `IamAccessDeniedException`/
  `IamUnavailableException` propagate — `GlobalExceptionHandler`
  handles the rendering, not the interceptor itself.
- `AuthWebMvcConfigurer implements WebMvcConfigurer`: registers
  `AuthInterceptor` via `addInterceptors`, with
  `.excludePathPatterns("/healthz")`.

## 6. Action/resource mapping

| S3 operation | Method + Path | Action | Resource |
|---|---|---|---|
| CreateBucket | `PUT /{bucket}` | `s3:CreateBucket` | `arn:cloudlite:s3:::{bucket}` |
| ListBuckets | `GET /` | `s3:ListAllMyBuckets` | `arn:cloudlite:s3:::*` |
| HeadBucket | `HEAD /{bucket}` | `s3:ListBucket` | `arn:cloudlite:s3:::{bucket}` |
| DeleteBucket | `DELETE /{bucket}` | `s3:DeleteBucket` | `arn:cloudlite:s3:::{bucket}` |
| PutObject | `PUT /{bucket}/{key}` | `s3:PutObject` | `arn:cloudlite:s3:::{bucket}/{key}` |
| GetObject | `GET /{bucket}/{key}` | `s3:GetObject` | `arn:cloudlite:s3:::{bucket}/{key}` |
| HeadObject | `HEAD /{bucket}/{key}` | `s3:GetObject` | `arn:cloudlite:s3:::{bucket}/{key}` |
| DeleteObject | `DELETE /{bucket}/{key}` | `s3:DeleteObject` | `arn:cloudlite:s3:::{bucket}/{key}` |

Mirrors real AWS IAM action names (`HeadObject` requires
`s3:GetObject`; `HeadBucket` shares `s3:ListBucket`). The ARN shape
(`arn:cloudlite:s3:::<bucket>` / `arn:cloudlite:s3:::<bucket>/<key>`)
is the same one already used by IAM's own Phase 2 integration test, so
policies created there work against real S3 resources unmodified.

## 7. Error handling — fail-closed, AWS-shaped

Two distinct outcomes, both rendered through S3's existing
`GlobalExceptionHandler` (AWS-shaped XML, unchanged contract):

- **403 `AccessDenied`**: missing `Authorization` header, malformed
  header, or IAM explicitly returning `DENY`. Real AWS collapses all
  of these into one generic code — it deliberately never tells an
  unauthorized caller *why* ("bucket doesn't exist" vs. "you can't see
  it" vs. "you're not who you say you are" are all information leaks).
  Same posture here.
- **500 `InternalError`** (S3's existing, already non-leaky handler):
  IAM connection failure, timeout, or a 5xx from IAM. Kept distinct
  from 403 on purpose — this is what lets "IAM went down" show up as a
  spike in 500s in the observability stack, distinguishable from
  ordinary permission denials, for the interview narrative in
  `architecture.md` §12.

## 8. Testing

- **Existing 55 tests**: unchanged at the method level.
  `BucketServiceTest`/`ObjectServiceTest` never go through MVC
  dispatch, so they're untouched entirely. The three existing
  `@WebMvcTest` classes (`HealthControllerTest`, `BucketControllerTest`,
  `ObjectControllerTest`) each get one added `excludeFilters` attribute
  on their `@WebMvcTest` annotation, excluding `AuthInterceptor` and
  `AuthWebMvcConfigurer` from that slice's component scan — the
  interceptor bean is never constructed in those slices (sidesteps
  both the missing-`IamClient`-dependency problem and the need to stub
  an `Authorization` header on every existing test method).
- **New `AuthInterceptorTest`** (`@WebMvcTest` slice, `IamClient`
  mocked via `@MockBean`): ALLOW → chain continues; `DENY` →
  403 `AccessDenied`; missing header → 403 `AccessDenied` (iamclient
  never even called); `IamUnavailableException` → 500
  `InternalError`. Covers both a bucket-path and an object-path
  request so the action/resource mapping table itself is exercised,
  not just the interceptor's control flow.
- **New `IamClientTest`**: unit tests against a mocked HTTP layer
  (`MockRestServiceServer`) for the `ALLOW`/`DENY`/error-response/
  timeout cases — proving `IamClient` throws the right exception type
  for each IAM response shape.
- **One new integration test**, extending the pattern in
  `S3ApplicationIntegrationTest`: a lightweight HTTP stub standing in
  for IAM (a small test-scoped `@RestController` bound to a random
  port, configured as `iam.base-url` for the test) — not a real IAM
  Testcontainers instance, since that would make S3's own test suite
  depend on IAM's whole stack. Proves the full
  request → interceptor → iamclient → HTTP → decision → controller-or-403
  path end-to-end against the real Spring MVC dispatch pipeline.
- Manual/exploratory verification via `docker-compose up` (both `s3`
  and `iam`) + curl: create a user in IAM, attach a policy, get a
  token, call S3 with it.

## 9. Config / deployment

- `services/s3/src/main/resources/application.yml`: add
  ```yaml
  iam:
    base-url: ${IAM_BASE_URL:http://localhost:8081}
    connect-timeout-ms: 2000
    read-timeout-ms: 3000
  ```
- `docker-compose.yml`'s `s3` service gets one new environment
  variable: `IAM_BASE_URL: "http://iam:8081"` (compose's internal
  service-name DNS, matching how `s3`/`iam` already reach `postgres`).
- `docs/services/s3.md`'s "Dependencies" section updated to describe
  the wiring as built, not "wired in after both services exist" (that
  condition is now satisfied).

## 10. Open items for the implementation plan

- Exact `IamClient` method signature and internal record field names
  — left to the plan (no behavioral ambiguity, just implementation
  detail).
- Whether the lightweight IAM stub for the new integration test lives
  as a nested static class inside the test file or a separate test
  helper class — left to the plan.
- Whether `AuthInterceptor`'s bucket/key extraction needs any special
  handling for the root path (`GET /`, ListBuckets) versus the
  `/{bucket}` and `/{bucket}/{*key}` patterns — mechanical detail, left
  to the plan.
