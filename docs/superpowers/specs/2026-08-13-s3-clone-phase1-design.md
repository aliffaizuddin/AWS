# S3 Clone — Phase 1 (Foundation) Design

Date: 2026-08-13
Status: Approved, not yet planned/implemented

## 1. Context

`docs/services/s3.md` scopes the full S3 clone: bucket CRUD, object
PUT/GET/DELETE/HEAD with byte-range support, multipart upload with crash
recovery, versioning, and metadata/tags. That full scope is too large
for a single implementation plan, so it's split into build phases. This
document specs **Phase 1 only**: the foundation that later phases build
on.

Per `architecture.md` §11 (build order), the S3 clone is the anchor
service and is built standalone first, with no IAM auth wired in yet —
that happens in a later top-level build-order step.

## 2. Phase breakdown (for context — only Phase 1 is specced here)

1. **Phase 1 — Foundation** (this doc): project scaffolding, `/healthz`,
   Postgres schema + migrations, bucket CRUD, basic object
   PUT/GET/DELETE/HEAD. No ranges, no versioning, no multipart, no
   custom tags.
2. **Phase 2** — byte-range `GET`, custom object tags.
3. **Phase 3** — versioning (list/restore/prior versions).
4. **Phase 4** — multipart upload with crash recovery (initiate → upload
   parts → complete/abort; kill mid-upload and verify clean retry or
   orphan cleanup).

Phases 2–4 are out of scope for the implementation plan that follows
this doc; each gets its own brainstorm/spec when its turn comes.

## 3. Goals (Phase 1)

- Stand up `services/s3/` as a working Go service with real bucket and
  object operations backed by Postgres + local disk.
- Be wire-compatible with the real AWS S3 REST API for the operations
  in scope, so the real `aws` CLI / SDKs can point at it (with
  path-style addressing forced).
- Establish the storage/metadata split and schema shape that Phases
  2–4 will extend, without over-building for features that don't exist
  yet (no version column, no tag table, no multipart-upload tracking
  table in Phase 1).

## 4. Non-goals (Phase 1)

- Auth / IAM integration (later top-level build-order step).
- Byte-range `GET`, versioning, multipart upload, custom tags (later
  phases).
- Cross-region replication, lifecycle policies, server-side encryption,
  event notifications — see `docs/future-work.md` (out of scope for the
  whole S3 clone, not just Phase 1).

## 5. Architecture

A single Go binary, `services/s3/cmd/s3/main.go`, exposing an
AWS-S3-wire-compatible HTTP API. Path-style bucket/object addressing
(`http://host:port/{bucket}/{key}`) — no virtual-hosted-style, no
wildcard DNS needed for local/dev use.

```
Client (curl / aws-cli --endpoint-url / boto3 path-style)
        │
        ▼
services/s3 (Go, net/http)
        │
        ├── metadata/  ──▶ Postgres (buckets, objects tables)
        └── storage/   ──▶ local disk (<data-dir>/<storage_id>)
```

No IAM calls in Phase 1 — `internal/iamclient` (named in
`docs/services/s3.md`) is not built until IAM exists and is wired in,
per the top-level build order.

## 6. Components

### `services/s3/cmd/s3/`
`main.go` — reads config from env vars (DB DSN, data directory, listen
address), wires up `metadata`, `storage`, and `api`, starts the HTTP
server.

### `services/s3/internal/api/`
- HTTP routing via Go 1.22+ `net/http.ServeMux` method+wildcard
  patterns (e.g. `"PUT /{bucket}/{key...}"`) — no third-party router.
- Request parsing, response writing, and XML marshaling for both
  successful responses (e.g. `ListAllMyBucketsResult`) and errors (see
  §8).
- `/healthz` handler — returns 200 once a DB ping succeeds, 503
  otherwise.

### `services/s3/internal/metadata/`
- Postgres access via `sqlx` (thin wrapper over `database/sql` — no
  ORM).
- Schema, via `golang-migrate` migrations in
  `internal/metadata/migrations/`:

```sql
CREATE TABLE buckets (
    name        TEXT PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
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

- Repository-style Go functions (`CreateBucket`, `GetBucket`,
  `DeleteBucket`, `ListBuckets`, `PutObjectMeta`, `GetObjectMeta`,
  `DeleteObjectMeta`, `ListObjectsInBucket` (needed to check emptiness
  on bucket delete)).

### `services/s3/internal/storage/`
- Pure blob store: `Put(storageID uuid.UUID, r io.Reader) error`,
  `Get(storageID uuid.UUID) (io.ReadCloser, error)`,
  `Delete(storageID uuid.UUID) error`.
- Files live at `<data-dir>/<storage_id>` — flat, no bucket/key
  awareness. `metadata` owns the bucket/key → `storage_id` mapping.
- `<data-dir>` is env-configured; in `docker-compose` it's a bind mount,
  in Kubernetes (later, Platform phase) it'll be the `bulk-hdd`
  `StorageClass` PVC mount — not relevant to this phase's code, just
  noted so the config knob already exists.

## 7. API surface (Phase 1)

Path-style, all under the service's single listen address.

| Operation | Method + Path | Success | Key error cases |
|---|---|---|---|
| CreateBucket | `PUT /{bucket}` | 200, empty body | 409 `BucketAlreadyExists` |
| ListBuckets | `GET /` | 200, XML `ListAllMyBucketsResult` | — |
| HeadBucket | `HEAD /{bucket}` | 200, no body | 404 (no body on HEAD) |
| DeleteBucket | `DELETE /{bucket}` | 204 | 404 `NoSuchBucket`; 409 `BucketNotEmpty` if it has objects |
| PutObject | `PUT /{bucket}/{key}` | 200, `ETag` header | 404 `NoSuchBucket` if bucket missing |
| GetObject | `GET /{bucket}/{key}` | 200, body + `Content-Type`/`Content-Length`/`ETag`/`Last-Modified` | 404 `NoSuchKey` |
| HeadObject | `HEAD /{bucket}/{key}` | 200, same headers as GET, no body | 404 (no body on HEAD) |
| DeleteObject | `DELETE /{bucket}/{key}` | 204 always (idempotent — matches real S3 behavior; no error if key never existed) | — |

`ETag` is the hex MD5 digest of the object's bytes, matching real S3's
behavior for non-multipart uploads.

`Content-Type` on `PutObject` comes from the request's `Content-Type`
header (default `application/octet-stream` if absent) and is echoed
back on `GetObject`/`HeadObject`.

## 8. Error handling

AWS-shaped XML error body on every 4xx/5xx:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Error>
  <Code>NoSuchBucket</Code>
  <Message>The specified bucket does not exist</Message>
  <BucketName>example-bucket</BucketName>
  <RequestId>...</RequestId>
</Error>
```

Error codes used in Phase 1: `NoSuchBucket`, `NoSuchKey`,
`BucketAlreadyExists`, `BucketNotEmpty`. `RequestId` is a per-request
generated identifier (UUID is fine — doesn't need to match AWS's
opaque ID format), included so real SDK error-handling code paths (which
key off `Code`, not just HTTP status) behave correctly against this
clone.

## 9. Testing

- **Unit tests** (`testify`) for `api` handlers and `metadata` repository
  logic, against interfaces (`storage.Store`, a `metadata` repository
  interface) — no real Postgres or disk required, fast.
- **Integration tests** (`testify` + `testcontainers-go`): spin up a
  real Postgres container per test run, apply migrations, exercise the
  full `metadata` + `storage` + `api` path end-to-end (e.g. PUT a
  bucket, PUT an object, GET it back, assert ETag matches). Runs via
  plain `go test ./...` — no manual `docker-compose up` step required
  for CI or local test runs.
- Manual/exploratory verification via `docker-compose up` +
  `curl`/`aws --endpoint-url` per `architecture.md` §11 — this is for
  human sanity-checking against real tooling, not part of the automated
  test suite.

## 10. Local dev

Root `docker-compose.yml` (per `architecture.md` §9): Postgres +
`s3` service, both configured via env vars matching what `main.go`
reads. `/healthz` lets `docker-compose`'s own healthcheck (if added)
or a manual curl confirm the service is up and DB-connected.

## 11. Open items for the implementation plan

- Exact env var names (e.g. `S3_DB_DSN`, `S3_DATA_DIR`,
  `S3_LISTEN_ADDR`) — left to the plan/implementation, not a design-
  level decision.
- Whether bucket names get AWS's real naming-validation rules (DNS-safe,
  3–63 chars, etc.) enforced in Phase 1 or deferred — recommend
  enforcing from the start since it's cheap and avoids a later
  migration of existing bad names; final call left to implementation.
