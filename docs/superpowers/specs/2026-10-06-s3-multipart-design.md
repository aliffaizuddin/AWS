# S3 Multipart Upload with Crash Recovery (S3 Phase 4) — Design

Date: 2026-10-06
Status: Approved, not yet planned/implemented

## 1. Context

S3 Phase 1 (bucket CRUD, single-request object PUT/GET/DELETE/HEAD) is
built. The original S3 phase plan
(`2026-08-19-s3-clone-phase1-java-design.md` §1) listed Phase 2 (byte-range
GET + tags), Phase 3 (versioning) and Phase 4 (multipart with crash
recovery). None of 2–4 exist.

On 2026-10-06 the project's MVP was defined against the two interview
pitches in `architecture.md` §12: **S3 multipart with crash recovery** plus
**deploying the stack to the real bare-metal node**. Versioning, byte-range
GET and custom tags are not needed for either pitch and move to
`future-work.md` as part of this sub-project. Phase 4 does not depend on
Phases 2–3.

Facts about the current code that shape this design:

- `PUT /{bucket}/{*key}` buffers the whole body in memory (100 MiB cap,
  `ObjectService.MAX_OBJECT_SIZE`), writes it via `DiskBlobStore.put`
  (temp file → `fsync` → `ATOMIC_MOVE`), then upserts the `objects` row.
  A crash between rename and the DB upsert leaves an orphaned blob.
- `objects` has `storage_id UUID NOT NULL`; blobs live under `/data`
  named by UUID; temp files are `<uuid>.<uuid>.tmp`.
- `AuthInterceptor` maps `(method, bucket?, key?)` to an IAM action and
  rejects every `POST`.
- Errors are AWS-shaped XML via `S3ErrorCode` + `GlobalExceptionHandler`.
- S3 runs as a single replica (`architecture.md` §7).
- The chaos suite (`chaos/`) has a blob audit that treats any blob not in
  `objects.storage_id` as orphaned.

## 2. Goals

- AWS-wire-shaped multipart upload: create, upload part, complete, abort,
  list parts, list in-progress uploads.
- **Resumable:** uploaded parts survive an S3 restart; a client can
  rediscover its upload and continue.
- **Atomic complete:** a completed upload yields the whole object or the
  upload stays in progress — never a partial object.
- **Idempotent complete:** retrying complete after a lost response returns
  the original result.
- **Cleanup:** a reconciler expires abandoned uploads and garbage-collects
  orphaned blobs and stale temp files — including the existing
  single-PUT orphan window.
- A new chaos scenario proving the above against a real kill.

## 3. Non-goals

- Versioning, byte-range GET, custom tags (moved to `future-work.md`).
- Streaming request bodies to disk: parts are still buffered in memory,
  capped at 100 MiB like single PUTs.
- Pagination on list calls (`max-parts`, `max-uploads`, markers).
- `UploadPartCopy`, checksums other than MD5, SSE, object lock.
- AWS SigV4 auth — clients still use IAM-issued bearer JWTs, so the real
  `aws` CLI is not a target client.
- Multi-replica S3 (the reconciler assumes one replica).

## 4. Data model (Flyway `V3__multipart_uploads.sql`)

```sql
CREATE TABLE multipart_uploads (
    upload_id    UUID PRIMARY KEY,
    bucket_name  TEXT NOT NULL REFERENCES buckets(name),
    key          TEXT NOT NULL,
    content_type TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED','ABORTED')),
    etag         TEXT,                       -- set on complete
    initiated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX multipart_uploads_bucket_status ON multipart_uploads (bucket_name, status);

CREATE TABLE upload_parts (
    upload_id   UUID NOT NULL REFERENCES multipart_uploads(upload_id) ON DELETE CASCADE,
    part_number INT  NOT NULL CHECK (part_number BETWEEN 1 AND 10000),
    storage_id  UUID NOT NULL,
    size_bytes  BIGINT NOT NULL,
    etag        TEXT NOT NULL,               -- md5 hex of the part
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (upload_id, part_number)
);

ALTER TABLE objects ALTER COLUMN storage_id DROP NOT NULL;
ALTER TABLE objects ADD COLUMN upload_id UUID REFERENCES multipart_uploads(upload_id);
ALTER TABLE objects ADD CONSTRAINT objects_one_backing
    CHECK ((storage_id IS NULL) <> (upload_id IS NULL));
```

An object is backed by exactly one of: a single blob (`storage_id`), or a
completed upload (`upload_id`) whose `upload_parts` rows, ordered by
`part_number`, are the object's manifest. No separate object-parts table.

## 5. Operations

### Create — `POST /{bucket}/{key}?uploads`

Bucket must exist (`NoSuchBucket`). Inserts an `IN_PROGRESS` upload with a
random UUID and the request's `Content-Type` (default
`application/octet-stream`). Returns `InitiateMultipartUploadResult`
{Bucket, Key, UploadId}.

### Upload part — `PUT /{bucket}/{key}?partNumber=N&uploadId=X`

- `partNumber` outside 1–10000 → `InvalidArgument`.
- Upload must exist, be `IN_PROGRESS`, and match bucket+key → else
  `NoSuchUpload`.
- Body read with the existing bounded reader (100 MiB cap →
  `EntityTooLarge`). Blob written via `BlobStore.put` (same temp → fsync →
  rename path as single PUT).
- Upsert the `upload_parts` row; touch `multipart_uploads.updated_at`.
  If the part number already existed, the superseded blob is deleted
  after commit (best effort).
- Returns 200 with `ETag: "<md5hex>"`.

### Complete — `POST /{bucket}/{key}?uploadId=X`

Body: `CompleteMultipartUpload` {Part{PartNumber, ETag}…}. One transaction:

1. `SELECT … FOR UPDATE` the upload; must match bucket+key.
   - `COMPLETED` → return the stored result (idempotent retry), no changes.
   - `ABORTED` / missing → `NoSuchUpload`.
2. Validate: body parses and lists ≥1 part (`MalformedXML`); part numbers
   strictly ascending (`InvalidPartOrder`); each listed part exists with a
   matching ETag, quotes ignored (`InvalidPart`); every listed part except
   the last is ≥ 5 MiB (`EntityTooSmall`).
3. Delete `upload_parts` rows not listed.
4. Upsert the `objects` row for (bucket, key): `upload_id = X`,
   `storage_id = NULL`, `size_bytes` = sum of listed parts,
   `content_type` from the upload, `etag` = multipart ETag.
5. Set upload `status = COMPLETED`, `etag`, `updated_at`.

After commit (best effort; the reconciler catches misses): delete blobs of
unlisted parts, and the backing of any superseded object (its single blob,
or its previous upload's parts).

**Multipart ETag:** `hex(md5(concat(binary md5 of each listed part)))`
followed by `-<part count>`, matching AWS.

Returns `CompleteMultipartUploadResult` {Location, Bucket, Key, ETag}.

### Abort — `DELETE /{bucket}/{key}?uploadId=X`

`IN_PROGRESS` → one transaction: status `ABORTED`, delete its part rows;
then delete their blobs (best effort). Returns 204. `ABORTED`, `COMPLETED`
or missing → `NoSuchUpload`.

### List parts — `GET /{bucket}/{key}?uploadId=X`

`IN_PROGRESS` only (else `NoSuchUpload`). `ListPartsResult` with
{Bucket, Key, UploadId, Part{PartNumber, ETag, Size, LastModified}…}
ordered by part number. No pagination.

### List uploads — `GET /{bucket}?uploads`

`ListMultipartUploadsResult` {Bucket, Upload{Key, UploadId, Initiated}…}
for `IN_PROGRESS` uploads only, ordered by key then initiated time. No
pagination. This is how a client that lost its upload ID resumes.

### GET / HEAD / DELETE of a multipart object

- GET streams each part blob in `part_number` order as one body;
  `Content-Length` = stored size; ETag = multipart ETag.
- HEAD unchanged apart from reading the multipart ETag/size.
- DELETE: one transaction removes the object row and the upload (cascade
  removes part rows); then part blobs are deleted (best effort).
- Single PUT over an existing multipart object supersedes it: the old
  upload's rows and blobs are removed the same way.

### Routing

Spring `params` conditions select handlers: `params = "uploads"`,
`params = "uploadId"`, `params = {"partNumber","uploadId"}`. Existing
single-object handlers keep `params = "!uploadId"` (and the bucket GET
`"!uploads"`) so plain requests are unaffected.

## 6. IAM mapping (`AuthInterceptor`)

| Request | Action |
|---|---|
| `POST /{bucket}/{key}?uploads` | `s3:PutObject` |
| `PUT /{bucket}/{key}?partNumber&uploadId` | `s3:PutObject` |
| `POST /{bucket}/{key}?uploadId` | `s3:PutObject` |
| `DELETE /{bucket}/{key}?uploadId` | `s3:AbortMultipartUpload` |
| `GET /{bucket}/{key}?uploadId` | `s3:ListMultipartUploadParts` |
| `GET /{bucket}?uploads` | `s3:ListBucketMultipartUploads` (resource `arn:cloudlite:s3:::<bucket>`) |

Any other `POST` stays denied. Object resources are
`arn:cloudlite:s3:::<bucket>/<key>` as today.

## 7. Error codes (added to `S3ErrorCode`)

| Code | Status | When |
|---|---|---|
| `NoSuchUpload` | 404 | upload missing, aborted, completed (non-complete ops), or bucket/key mismatch |
| `InvalidPart` | 400 | listed part missing or ETag mismatch |
| `InvalidPartOrder` | 400 | part numbers not strictly ascending |
| `EntityTooSmall` | 400 | non-last listed part < 5 MiB |
| `MalformedXML` | 400 | complete body unparseable or no parts |

`InvalidArgument` (existing) covers a bad `partNumber`.

## 8. Reconciler

`MultipartReconciler`, a Spring `@Scheduled` component: runs once on
`ApplicationReadyEvent`, then every `s3.reconcile.interval`
(`S3_RECONCILE_INTERVAL`, default `10m`). Steps run independently — an
exception in one is logged and the next still runs.

1. **Expire abandoned uploads:** `IN_PROGRESS` with `updated_at` older than
   `s3.multipart.ttl` (`S3_MULTIPART_TTL`, default `24h`) → aborted via the
   same path as Abort. Every part upload touches `updated_at`, so a slow
   active upload is never reaped.
2. **Prune upload rows:** delete `COMPLETED` uploads no `objects` row
   references, and `ABORTED` uploads older than the TTL.
3. **Blob GC:** live set = `objects.storage_id` ∪ `upload_parts.storage_id`.
   Delete every UUID-named file under the data dir that is not live **and**
   whose mtime is older than `s3.reconcile.grace`
   (`S3_RECONCILE_GRACE`, default `1h`), and every `*.tmp` older than the
   grace. The grace protects blobs written by in-flight requests that have
   not committed their row yet. This also closes the single-PUT orphan
   window.

**Single-replica assumption:** no cross-instance locking. Documented; a
Postgres advisory lock is the `future-work.md` path to multiple replicas.

**Metrics** (registered at startup with value 0, avoiding the
lazily-created-counter gotcha in `observability.md`):
`s3_reconciler_uploads_expired_total`, `s3_reconciler_blobs_deleted_total`,
`s3_reconciler_runs_total{outcome}`, gauge `s3_multipart_uploads_in_progress`.

**Logging:** one INFO summary line per run; DEBUG per deleted item.

## 9. Testing

All TDD, using the existing JUnit 5 + Testcontainers Postgres + MockMvc
setup.

- **Repository (Testcontainers):** V3 migration applies; the
  `objects_one_backing` check rejects both/neither; part upsert replaces;
  cascade on upload delete.
- **`MultipartService` unit:** multipart ETag against a known AWS example;
  each validation error; complete retry on `COMPLETED` returns the stored
  result; ops on `ABORTED` → `NoSuchUpload`; abort.
- **Complete atomicity (fault injection):** a test makes the complete
  transaction throw after the `objects` upsert; afterwards the object does
  not exist, the upload is `IN_PROGRESS`, and all parts are intact.
- **Reconciler:** TTL expiry; an upload with a recent part is kept; GC
  respects grace; live blobs never deleted; `.tmp` cleanup; one failing
  step does not stop the others.
- **Controller + `AuthInterceptor`:** query-param routing, XML shapes,
  IAM action per operation, plain PUT/GET/DELETE unaffected.
- **Integration:** create → 3 parts → complete → GET bytes equal the
  concatenation; abort; list uploads/parts.

## 10. Chaos suite changes

- **Scenario `05-kill-s3-mid-multipart.sh`:**
  1. Create an upload; PUT parts 1–2 (5 MiB each, sha256s recorded).
  2. Start part 3 throttled; kill S3 mid-transfer.
  3. After recovery: `GET ?uploads` finds the upload (PASS/FAIL);
     `ListParts` shows parts 1–2 with their ETags (PASS/FAIL).
  4. Re-PUT part 3, PUT part 4 (last, small), complete; GET → sha256 of
     the concatenation matches (PASS/FAIL).
  5. Second upload: parts uploaded, fire complete and kill S3 immediately;
     after recovery, retry complete → 200 with the same ETag as a
     locally computed multipart ETag, and the object's sha256 matches
     (PASS/FAIL). Also records whether the first complete had committed
     before the kill (INFO).
- **Blob audit:** live set includes `upload_parts.storage_id`.
- **Test policy:** add `s3:AbortMultipartUpload`,
  `s3:ListMultipartUploadParts`, `s3:ListBucketMultipartUploads`.
- **Teardown:** abort any in-progress uploads for the run's keys before
  deleting objects/bucket.

## 11. Docs

- `docs/services/s3.md`: Phase 4 built; multipart section; status line
  updated; Phases 2–3 marked deferred to `future-work.md`.
- `docs/future-work.md`: versioning, byte-range GET, custom tags (with
  the MVP rationale); list pagination; reconciler advisory lock for
  multiple replicas; streaming part bodies to disk.
- `docs/platform/chaos.md`: scenario 05 and its first-run findings.

## 12. Open items for the implementation plan

- Exact bounded-reader reuse for part bodies (`ObjectController`'s
  `readBoundedBody`) vs. extracting it to a shared helper.
- How GET streams multiple blobs (a `SequenceInputStream` over lazily
  opened part streams vs. a custom `StreamingResponseBody`) — pick one
  that closes every stream on client disconnect.
- Whether Spring's `params` routing needs the existing single-object
  mappings to gain explicit negative conditions, confirmed by the
  controller tests.
- Chaos scenario 05's timing for "kill right after complete" — S3's
  complete is a fast transaction, so the kill may land before or after
  commit; both are valid and the scenario must accept either.
