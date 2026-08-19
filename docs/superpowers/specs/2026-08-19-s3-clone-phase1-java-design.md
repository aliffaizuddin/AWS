# S3 Clone — Phase 1 (Foundation), Java Rewrite — Design

Date: 2026-08-19
Status: Approved, not yet planned/implemented

**Supersedes:** [`2026-08-13-s3-clone-phase1-design.md`](2026-08-13-s3-clone-phase1-design.md)
for **technology stack only**. That doc's behavioral requirements (API
surface, error shapes, edge-case handling) carry forward unchanged and
are restated here for completeness; only the implementation language
and framework choices change.

## 1. Context

`architecture.md` was revised on 2026-08-19 to change the S3/IAM
backend language from Go to Java (Spring Boot, Java 21+ virtual
threads) — see the "Key decisions" table (§3) and `future-work.md`'s
new "Language strategy" section, which now explicitly lists porting
S3/IAM *to* Go as out of scope.

The existing `feat/s3-clone-phase1` branch (PR #6) implemented this
same Phase 1 scope entirely in Go — 14 commits, a full `internal/{api,
storage,metadata}` package tree, tests, `docker-compose` wiring. Under
the revised architecture, that implementation is off-spec for language
even though its behavior and design are otherwise still correct and
already through one whole-branch review. This document specs a
rewrite of that same Phase 1 scope in Java/Spring Boot, on a new
branch off `main`. The Go branch/PR are left untouched as a shelved
reference.

## 2. Phase breakdown (unchanged, for context — only Phase 1 is specced here)

1. **Phase 1 — Foundation** (this doc): project scaffolding, `/healthz`,
   Postgres schema + migrations, bucket CRUD, basic object
   PUT/GET/DELETE/HEAD. No ranges, no versioning, no multipart, no
   custom tags.
2. **Phase 2** — byte-range `GET`, custom object tags.
3. **Phase 3** — versioning (list/restore/prior versions).
4. **Phase 4** — multipart upload with crash recovery.

Phases 2–4 are out of scope here; each gets its own brainstorm/spec
when its turn comes.

## 3. Goals (Phase 1)

- Stand up `services/s3/` as a working Spring Boot service with real
  bucket and object operations backed by Postgres + local disk.
- Be wire-compatible with the real AWS S3 REST API for the operations
  in scope (path-style addressing), same as the Go version.
- Match the Go implementation's behavior exactly where the new
  architecture doesn't dictate otherwise — same endpoints, status
  codes, XML error shapes, and edge-case handling (reserved bucket
  name, delete-then-blob ordering, orphan-blob logging, non-leaky
  internal errors) that already went through one whole-branch review.
- Follow `architecture.md`'s Java repo-structure convention
  (`controller/`, `service/`, `repository/`), not the Go tree's
  `cmd/`, `internal/{api,storage,metadata}` layout.

## 4. Non-goals (Phase 1)

- Auth / IAM integration (later top-level build-order step; IAM
  service doesn't exist yet).
- Byte-range `GET`, versioning, multipart upload, custom tags (later
  phases).
- Cross-region replication, lifecycle policies, server-side encryption,
  event notifications — see `docs/future-work.md`.
- Porting the Go code mechanically — this is a from-scratch Spring
  Boot implementation, using Go's behavior as the spec, not its code
  as a template. Where Spring idioms simplify something the Go version
  had to work around (see §6, routing), take the simplification.

## 5. Architecture

A single Spring Boot application (`services/s3`), exposing the same
AWS-S3-wire-compatible HTTP API, path-style addressing only.

```
Client (curl / aws-cli --endpoint-url / boto3 path-style)
        │
        ▼
services/s3 (Spring Boot, Spring MVC + virtual threads)
        │
        ├── controller/ ─▶ service/ ─▶ repository/ ──▶ Postgres (JPA/Hibernate)
        │                       │
        │                       └──▶ storage/ (DiskBlobStore) ──▶ local disk
```

No IAM calls in Phase 1 — `iamclient/` (named in `architecture.md`'s
repo structure) is not built until IAM exists and is wired in, per the
top-level build order.

`spring.threads.virtual.enabled=true` — plain blocking Spring MVC
controllers, virtual threads absorb the concurrent-request cost that
the architecture doc's rationale calls out, no reactive programming
model needed.

## 6. Components

### `controller/`
- `BucketController`, `ObjectController` — thin: bind HTTP request to
  service call, map the service's result/exception to an HTTP
  response. No business logic here (existence checks, orphan cleanup,
  delete ordering all live in `service/`).
- **Routing simplification vs. the Go version:** Spring's
  `PathPattern` supports `{*key}` (capture-remaining-path-segments)
  directly, so `/{bucket}/{*key}` replaces Go's `"{key...}"` wildcard
  with no extra work. Spring MVC also auto-handles `HEAD` for any
  `GET`-mapped route, so the Go router's method-agnostic dispatch
  workaround (needed only to dodge a `net/http.ServeMux`
  pattern-conflict panic between `GET /healthz` and `HEAD /{bucket}`)
  is dropped entirely — bucket/object HEAD just map normally.
- The `""`/`"healthz"` reserved bucket-name rejection is kept as an
  app-level business rule in `BucketService` (still a deliberate
  reserved name), independent of routing.

### `service/`
- `BucketService` — create (reserved-name + duplicate-name checks),
  get, delete (emptiness check via `ObjectRepository`), list.
- `ObjectService` — put (bucket-exists check, MD5 `ETag` computation,
  write blob before metadata commit, delete the *superseded* blob
  best-effort after a successful overwrite), get, head, delete (delete
  metadata row before blob, matching the Go version's ordering
  rationale: a failed blob delete after a successful metadata delete
  leaves a harmless orphaned blob; the reverse order can leave a
  metadata row pointing at nothing, which 500s forever and blocks
  bucket deletion).
- Both throw a shared `S3ApiException(code, httpStatus, message,
  resource)` for domain errors (`NoSuchBucket`, `NoSuchKey`,
  `BucketAlreadyExists`, `BucketNotEmpty`, `InvalidBucketName`); a
  `@RestControllerAdvice` renders it as AWS-shaped XML (see §8).
  Unexpected exceptions are logged server-side with full detail and
  rendered to the client as a generic `InternalError` body — same
  non-leaky-error rule the Go version's review enforced.

### `repository/`
- Spring Data JPA: `BucketRepository extends JpaRepository<Bucket,
  String>`, `ObjectRepository extends
  JpaRepository<ObjectMetadata, ObjectMetadataId>` (composite key:
  `bucketName` + `key`, no version history in Phase 1 — one row per
  key, same as the Go schema).
- `ObjectRepository.existsByBucketNameAndKey...` /
  `existsByBucketName(String bucketName)` replaces the Go version's
  hand-written `HasObjects` existence query.
- `ObjectRepository.upsert` for put: JPA's `save()` on an entity with
  the same composite key naturally does an update (Hibernate merge),
  matching the Go version's `ON CONFLICT ... DO UPDATE` semantics.

### `storage/` (`DiskBlobStore`)
- Same shape as the Go `Store` interface: `put(UUID id, InputStream)`,
  `get(UUID id): InputStream`, `delete(UUID id)`. Content-agnostic, no
  bucket/key awareness — that mapping lives in `repository/`.
- Atomic write: write to `<data-dir>/<uuid>.tmp`, `FileChannel.force(true)`
  (fsync) before close, then `Files.move(tmp, final, ATOMIC_MOVE)` —
  same crash-safety property as the Go version's temp-file-then-rename.
- `<data-dir>` is env/property-configured; `bulk-hdd` `StorageClass`
  mount point in the later Kubernetes phase, not relevant to this
  phase's code.

### Persistence — schema

Same two tables as the Go version, ported to Flyway migrations
(`src/main/resources/db/migration/V1__create_buckets.sql`,
`V2__create_objects.sql`):

```sql
CREATE TABLE buckets (
    name       TEXT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE objects (
    bucket_name  TEXT NOT NULL REFERENCES buckets(name),
    key          TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes   BIGINT NOT NULL,
    etag         TEXT NOT NULL,
    storage_id   UUID NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bucket_name, key)
);
```

(Single row per `(bucket_name, key)` in Phase 1 — no version history.
Phase 3 will need to change this shape; not designed here.)

## 7. API surface (Phase 1) — unchanged from the Go design

| Operation | Method + Path | Success | Key error cases |
|---|---|---|---|
| CreateBucket | `PUT /{bucket}` | 200, empty body | 409 `BucketAlreadyExists`; 400 `InvalidBucketName` (empty or `healthz`) |
| ListBuckets | `GET /` | 200, XML `ListAllMyBucketsResult` | — |
| HeadBucket | `HEAD /{bucket}` | 200, no body | 404 (no body on HEAD) |
| DeleteBucket | `DELETE /{bucket}` | 204 | 404 `NoSuchBucket`; 409 `BucketNotEmpty` if it has objects |
| PutObject | `PUT /{bucket}/{key}` | 200, `ETag` header | 404 `NoSuchBucket` if bucket missing |
| GetObject | `GET /{bucket}/{key}` | 200, body + `Content-Type`/`Content-Length`/`ETag`/`Last-Modified` | 404 `NoSuchKey` |
| HeadObject | `HEAD /{bucket}/{key}` | 200, same headers as GET, no body | 404 (no body on HEAD) |
| DeleteObject | `DELETE /{bucket}/{key}` | 204 always (idempotent) | — |

`ETag` is the hex MD5 digest of the object's bytes. `Content-Type` on
`PutObject` comes from the request header (default
`application/octet-stream` if absent) and is echoed back on
`GetObject`/`HeadObject`. `PutObject` caps request body size (100 MiB,
matching the Go version's `maxObjectSize` — no auth in this phase, so
an unbounded read is a real DoS risk).

`GET /healthz` — 200 if a DB health check (Spring Boot Actuator or a
manual `SELECT 1`) succeeds, 503 otherwise.

## 8. Error handling — unchanged shape from the Go design

AWS-shaped XML error body on every 4xx/5xx, rendered by a
`@RestControllerAdvice`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Error>
  <Code>NoSuchBucket</Code>
  <Message>The specified bucket does not exist</Message>
  <Resource>example-bucket</Resource>
  <RequestId>...</RequestId>
</Error>
```

Status-code mapping: `NoSuchBucket`/`NoSuchKey` → 404,
`BucketAlreadyExists`/`BucketNotEmpty` → 409, `InvalidBucketName` →
400, unmatched route → 404 `NotFound`, unhandled exception →
500 `InternalError` (generic message; real exception logged
server-side only — never leak SQL fragments, column names, or
filesystem paths to the client, per the Go branch's review finding).
`RequestId` is a per-request generated UUID.

XML (de)serialization via Jackson's `jackson-dataformat-xml`
(`XmlMapper`) rather than JAXB — less boilerplate, and Spring Boot's
Jackson starter is already a transitive dependency of
`spring-boot-starter-web`.

## 9. Testing

- **Unit tests** (JUnit 5 + Mockito) for `service/` layer logic against
  mocked repositories/store — no real Postgres or disk, fast.
- **Controller tests** (`@WebMvcTest` + `MockMvc`) for `controller/`,
  mirroring the Go version's handler tests against fakes.
- **Integration tests** (Testcontainers' Postgres module + `@SpringBootTest`):
  real Postgres container, Flyway migrations applied automatically on
  context startup, exercise the full `repository` → `service` →
  `controller` path (PUT a bucket, PUT an object, GET it back, assert
  ETag matches) — same intent as the Go version's `testcontainers-go`
  suite, no manual `docker-compose up` step required for CI or local
  `mvn test` runs.
- Manual/exploratory verification via `docker-compose up` +
  `curl`/`aws --endpoint-url`, same as before.

## 10. Local dev / deployment

- Multi-stage `Dockerfile`: `maven:3-eclipse-temurin-21` build stage →
  `eclipse-temurin:21-jre` runtime stage (replaces the Go
  `golang:1.25-alpine` → `alpine:3.20` two-stager).
- `docker-compose.yml`'s `s3` service updated for the JVM: set `-Xmx`
  to fit the container's memory allocation (per `architecture.md` §8's
  JVM tuning notes), and lengthen the healthcheck's start-period —
  Spring Boot's JVM warm-up is not instant like the Go binary's
  startup.
- Config: `application.yml` + env var overrides. Keep `S3_DATA_DIR` as
  a custom property (`s3.data-dir`), but switch DB config to Spring's
  standard `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` /
  `SPRING_DATASOURCE_PASSWORD` instead of the Go version's single
  `S3_DB_DSN` string — idiomatic Spring Boot convention.
- `docs/services/s3.md` updated to describe the Java/Spring/Maven
  stack and this doc + its implementation plan, in place of the Go
  references.

## 11. Open items for the implementation plan

- Exact `application.yml` property names beyond the DB/data-dir ones
  called out above — left to the plan.
- Whether bucket names get AWS's real naming-validation rules
  (DNS-safe, 3–63 chars) enforced now or deferred — same open item as
  the Go design; recommend enforcing from the start, final call left
  to implementation.
- Whether `ObjectMetadataId` (the composite JPA key) is a
  `@IdClass` or an `@EmbeddedId` — implementation detail, no behavioral
  difference; left to the plan.
