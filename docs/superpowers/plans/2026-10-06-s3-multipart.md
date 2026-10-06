# S3 Multipart Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AWS-shaped multipart upload for the S3 clone — resumable parts, atomic and idempotent complete, a reconciler that expires abandoned uploads and garbage-collects orphaned blobs — proven by unit/integration tests and a new chaos scenario.

**Architecture:** Two new tables (`multipart_uploads`, `upload_parts`); an object is backed by either a single blob or a completed upload whose parts are its manifest. `MultipartService` does each state change in one Postgres transaction (via `TransactionOperations`) and deletes blobs only after commit, best effort; `MultipartReconciler` sweeps what best effort misses. A new `MultipartController` routes on query parameters so existing object handlers are untouched.

**Tech Stack:** Java 21, Spring Boot 3.3.4 (MVC, Data JPA/Hibernate 6, Flyway, Actuator/Micrometer), jackson-dataformat-xml, JUnit 5 + Mockito + Testcontainers Postgres 16, bash chaos suite.

**Spec:** `docs/superpowers/specs/2026-10-06-s3-multipart-design.md`

## Global Constraints

- Part numbers 1–10000; every listed part except the last ≥ 5 MiB (`5L * 1024 * 1024`); part body cap 100 MiB (`ObjectService.maxObjectSize()`).
- Multipart ETag = `hex(md5(concat(binary md5 of each listed part))) + "-" + partCount`.
- Error codes: `NoSuchUpload` 404, `InvalidPart` 400, `InvalidPartOrder` 400, `EntityTooSmall` 400, `MalformedXML` 400; bad `partNumber` → existing `InvalidArgument`.
- IAM actions: create/part/complete → `s3:PutObject`; abort → `s3:AbortMultipartUpload`; list parts → `s3:ListMultipartUploadParts`; list uploads → `s3:ListBucketMultipartUploads` (resource `arn:cloudlite:s3:::<bucket>`).
- Upload statuses exactly `IN_PROGRESS | COMPLETED | ABORTED`.
- Config (Spring property / env, default): `s3.reconcile.interval` / `S3_RECONCILE_INTERVAL` `10m`; `s3.multipart.ttl` / `S3_MULTIPART_TTL` `24h`; `s3.reconcile.grace` / `S3_RECONCILE_GRACE` `1h`; `s3.reconcile.enabled` (default `true`).
- Metrics: `s3.reconciler.uploads.expired`, `s3.reconciler.blobs.deleted`, `s3.reconciler.runs{outcome=ok|partial}` (counters, registered at 0 on startup); gauge `s3.multipart.uploads.in_progress`.
- Blob deletes always happen **after** the transaction that unreferenced them commits, and are best effort.
- No pagination on list calls. Single S3 replica assumed.
- Run S3 tests from `services/s3`: `mvn -B -q test` (full) or `mvn -B -q test -Dtest=ClassName`. Count results with:
  `ls target/surefire-reports/*.txt | xargs grep -h "Tests run" | awk -F'[ ,]+' '{t+=$3; f+=$5; e+=$7} END {print "total="t" failures="f" errors="e}'`
- Commits: Conventional Commits; branch `feat/s3-multipart` off `main` (after the spec/plan PR merges; otherwise off `docs/s3-multipart-design`).

### Decided while planning (not in the spec)

- **Entity field `objectKey`, column `key`.** `key` is a JPQL reserved word; derived queries on an attribute named `key` are fragile in Hibernate 6.
- **Bucket delete:** `BucketNotEmpty` if the bucket has `IN_PROGRESS` uploads; otherwise its `COMPLETED`/`ABORTED` upload rows are purged first (they'd violate the FK). Without this, deleting a bucket after aborting an upload returns 500.
- **Scheduling via `SchedulingConfigurer` + `FixedDelayTask(Runnable, Duration, Duration)`**, not `@Scheduled(fixedDelayString=…)`: Spring 6.1's `@Scheduled` doesn't parse `10m`; Boot's `Duration` binding does.
- **`TransactionOperations` injected** into `MultipartService` and `UploadCleaner` (Boot's `TransactionTemplate` bean); unit tests pass `TransactionOperations.withoutTransaction()`.
- **Bad `uploadId` (not a UUID) → `NoSuchUpload`**, as AWS does; bad `partNumber` (non-integer or out of range) → `InvalidArgument`. Parsed manually so Spring's type-mismatch exceptions never reach the catch-all 500.
- **`PUT` with only one of `partNumber`/`uploadId` → `InvalidArgument`** via explicit guard mappings, so it can never fall through to a plain object PUT that overwrites the object.
- **Complete request body** is read raw (cap 2 MiB) and parsed with an `XmlMapper`, regardless of `Content-Type` — `curl -d` sends `application/x-www-form-urlencoded`.
- **Shared helpers extracted:** `util.Md5` (from `ObjectService.md5Hex`) and `controller.RequestBodies` (from `ObjectController.readBoundedBody`/`validateContentType`) — spec open item 1.
- **Multipart GET streaming:** `SequenceInputStream` over a lazily-opening `Enumeration` of part blobs — only the current part's file is open; `close()` closes it (spec open item 2).
- **Chaos `mp_etag` computed in pure bash** (`printf '%b'` of `\xHH` pairs | `md5sum`) — no python/xxd dependency.

## Review Focus

1. **Deleting a bucket that has (or had) multipart uploads** — in-progress → `BucketNotEmpty` 409; only aborted/completed history → bucket deleted, no 500. Pinned by `deleteRejectsWhileUploadsAreInProgress` and `deletePurgesFinishedUploadRowsFirst` (Task 5) and the integration test `abortedUploadDoesNotBlockBucketDelete` (Task 10).
2. **Overwriting or deleting a multipart object** — single PUT over it, DELETE of it, and a second multipart complete over it all leave no stale upload rows or live part blobs. Pinned by `putOverMultipartObjectDiscardsItsUpload`, `deleteMultipartObjectDiscardsItsUpload` (Task 5) and `completeOverExistingMultipartObjectDiscardsPreviousUpload` (Task 3).
3. **A `PUT` carrying only `partNumber` or only `uploadId`** — must be `InvalidArgument`, never a silent whole-object overwrite. Pinned by `putWithOnlyPartNumberIsInvalidArgument` / `putWithOnlyUploadIdIsInvalidArgument` (Task 6).
4. **Complete body edge cases** — empty body, non-XML, AWS namespace declaration, quoted vs unquoted ETags, a single `<Part>`. Pinned by `CompleteRequestParserTest` (Task 6).
5. **Reconciler racing an active upload** — an upload whose last part landed after the TTL cutoff was computed must not be aborted (cutoff re-checked under the row lock). Pinned by `expireSkipsUploadTouchedAfterTheCutoff` (Task 3).

---

## File Structure

```
services/s3/src/main/resources/db/migration/V3__multipart_uploads.sql   NEW
services/s3/src/main/java/dev/cloudlite/s3/
  domain/  UploadStatus.java  MultipartUpload.java  UploadPart.java  UploadPartId.java   NEW
           ObjectMetadata.java                                        MODIFY (nullable storageId, uploadId)
  repository/ MultipartUploadRepository.java  UploadPartRepository.java  NEW
              ObjectRepository.java                                   MODIFY (findAllStorageIds)
  util/    Md5.java                                                   NEW
  error/   S3ErrorCode.java                                           MODIFY (5 codes)
  service/ UploadCleaner.java  MultipartService.java  CompletedPart.java  CompletionResult.java  NEW
           ObjectService.java  BucketService.java                     MODIFY
  storage/ BlobEntry.java                                             NEW
           BlobStore.java  DiskBlobStore.java                         MODIFY (entries, deleteEntry)
  dto/     InitiateMultipartUploadResultXml.java  CompleteMultipartUploadXml.java  CompletedPartXml.java
           CompleteMultipartUploadResultXml.java  ListPartsResultXml.java  PartXml.java
           ListMultipartUploadsResultXml.java  UploadXml.java         NEW
  controller/ RequestBodies.java  CompleteRequestParser.java  MultipartController.java  NEW
              ObjectController.java                                   MODIFY (params guards, helpers)
  iamclient/ AuthInterceptor.java                                     MODIFY (query-param actions)
  reconcile/ MultipartReconciler.java  ReconcileReport.java  ReconcileSchedulingConfig.java  NEW
  config/  ClockConfig.java                                           NEW
services/s3/src/main/resources/application.yml                        MODIFY
services/s3/src/test/java/dev/cloudlite/s3/... matching tests           NEW/MODIFY
chaos/lib/s3xml.sh  chaos/test/test_s3xml.sh  chaos/scenarios/05-kill-s3-mid-multipart.sh   NEW
chaos/lib/cluster.sh  chaos/run.sh                                     MODIFY
docs/services/s3.md  docs/future-work.md  docs/platform/chaos.md      MODIFY
```

---

### Task 1: Schema, entities, repositories

**Files:**
- Create: `services/s3/src/main/resources/db/migration/V3__multipart_uploads.sql`, `domain/UploadStatus.java`, `domain/MultipartUpload.java`, `domain/UploadPart.java`, `domain/UploadPartId.java`, `repository/MultipartUploadRepository.java`, `repository/UploadPartRepository.java`
- Modify: `domain/ObjectMetadata.java`, `repository/ObjectRepository.java`
- Test: `src/test/java/dev/cloudlite/s3/repository/MultipartRepositoryTest.java`

(All Java paths below are under `services/s3/src/main/java/dev/cloudlite/s3/` unless shown in full.)

**Interfaces:**
- Produces:
  - `enum UploadStatus { IN_PROGRESS, COMPLETED, ABORTED }`
  - `MultipartUpload(UUID uploadId, String bucketName, String objectKey, String contentType)` → status `IN_PROGRESS`; `boolean belongsTo(String bucket, String key)`, `void touch()`, `void complete(String etag)`, `void abort()`; getters `getUploadId()`, `getBucketName()`, `getObjectKey()`, `getContentType()`, `getStatus()`, `getEtag()`, `getInitiatedAt()`, `getUpdatedAt()`.
  - `UploadPart(UUID uploadId, int partNumber, UUID storageId, long sizeBytes, String etag)`; `getUploadId()`, `getPartNumber()`, `getStorageId()`, `getSizeBytes()`, `getEtag()`, `getUpdatedAt()`.
  - `UploadPartId(UUID uploadId, int partNumber)` (`equals`/`hashCode`).
  - `ObjectMetadata.multipart(String bucket, String key, String contentType, long size, String etag, UUID uploadId)`; `UUID getUploadId()`; `boolean isMultipart()`; `getStorageId()` may be `null`.
  - `MultipartUploadRepository extends JpaRepository<MultipartUpload, UUID>`: `findForUpdate(UUID)`, `findByBucketNameAndStatusOrderByObjectKeyAscInitiatedAtAsc(String, UploadStatus)`, `findByStatusAndUpdatedAtBefore(UploadStatus, OffsetDateTime)`, `existsByBucketNameAndStatus(String, UploadStatus)`, `countByStatus(UploadStatus)`, `findUnreferencedCompletedIds()`, `deleteFinishedByBucketName(String)`.
  - `UploadPartRepository extends JpaRepository<UploadPart, UploadPartId>`: `findByIdUploadIdOrderByIdPartNumberAsc(UUID)`, `findAllStorageIds()`.
  - `ObjectRepository.findAllStorageIds()` (non-null single-blob ids).

- [ ] **Step 1: Create the branch**

```bash
git checkout main && git pull --ff-only
git checkout -b feat/s3-multipart
```

- [ ] **Step 2: Write the failing repository test**

`services/s3/src/test/java/dev/cloudlite/s3/repository/MultipartRepositoryTest.java`:
```java
package dev.cloudlite.s3.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cloudlite.s3.domain.Bucket;
import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.UploadPart;
import dev.cloudlite.s3.domain.UploadStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class MultipartRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private BucketRepository buckets;
    @Autowired private ObjectRepository objects;
    @Autowired private MultipartUploadRepository uploads;
    @Autowired private UploadPartRepository parts;

    private MultipartUpload newUpload(String bucket, String key) {
        buckets.save(new Bucket(bucket));
        return uploads.saveAndFlush(new MultipartUpload(UUID.randomUUID(), bucket, key, "text/plain"));
    }

    @Test
    void uploadRoundTripsWithInProgressStatus() {
        MultipartUpload u = newUpload("photos", "big.bin");

        MultipartUpload found = uploads.findForUpdate(u.getUploadId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(UploadStatus.IN_PROGRESS);
        assertThat(found.getObjectKey()).isEqualTo("big.bin");
        assertThat(found.belongsTo("photos", "big.bin")).isTrue();
        assertThat(found.belongsTo("photos", "other")).isFalse();
    }

    @Test
    void savingAPartWithTheSameNumberReplacesIt() {
        MultipartUpload u = newUpload("photos", "big.bin");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        parts.saveAndFlush(new UploadPart(u.getUploadId(), 1, first, 10, "aa"));
        parts.saveAndFlush(new UploadPart(u.getUploadId(), 1, second, 20, "bb"));

        var listed = parts.findByIdUploadIdOrderByIdPartNumberAsc(u.getUploadId());

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).getStorageId()).isEqualTo(second);
        assertThat(parts.findAllStorageIds()).containsExactly(second);
    }

    @Test
    void objectMustHaveExactlyOneBacking() {
        MultipartUpload u = newUpload("photos", "big.bin");
        objects.saveAndFlush(ObjectMetadata.multipart("photos", "big.bin", "text/plain", 30, "e-2", u.getUploadId()));

        assertThat(objects.findAllStorageIds()).isEmpty();
        assertThat(objects.findById(new dev.cloudlite.s3.domain.ObjectMetadataId("photos", "big.bin")).orElseThrow().isMultipart()).isTrue();
        assertThatThrownBy(() -> objects.saveAndFlush(
                ObjectMetadata.multipart("photos", "nobacking", "text/plain", 1, "e", null)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unreferencedCompletedUploadsAreFound() {
        MultipartUpload referenced = newUpload("photos", "a");
        MultipartUpload orphan = uploads.saveAndFlush(new MultipartUpload(UUID.randomUUID(), "photos", "b", "text/plain"));
        referenced.complete("x-1");
        orphan.complete("y-1");
        uploads.saveAndFlush(referenced);
        uploads.saveAndFlush(orphan);
        objects.saveAndFlush(ObjectMetadata.multipart("photos", "a", "text/plain", 1, "x-1", referenced.getUploadId()));

        assertThat(uploads.findUnreferencedCompletedIds()).containsExactly(orphan.getUploadId());
    }

    @Test
    void deleteFinishedByBucketNameKeepsInProgressAndCascadesParts() {
        MultipartUpload live = newUpload("photos", "a");
        MultipartUpload aborted = uploads.saveAndFlush(new MultipartUpload(UUID.randomUUID(), "photos", "b", "text/plain"));
        aborted.abort();
        uploads.saveAndFlush(aborted);
        parts.saveAndFlush(new UploadPart(aborted.getUploadId(), 1, UUID.randomUUID(), 1, "p"));

        int deleted = uploads.deleteFinishedByBucketName("photos");

        assertThat(deleted).isEqualTo(1);
        assertThat(uploads.findById(live.getUploadId())).isPresent();
        assertThat(parts.findByIdUploadIdOrderByIdPartNumberAsc(aborted.getUploadId())).isEmpty();
        assertThat(uploads.existsByBucketNameAndStatus("photos", UploadStatus.IN_PROGRESS)).isTrue();
    }

    @Test
    void staleUploadsAreFoundByStatusAndAge() {
        MultipartUpload u = newUpload("photos", "a");

        assertThat(uploads.findByStatusAndUpdatedAtBefore(UploadStatus.IN_PROGRESS, OffsetDateTime.now().plusMinutes(1)))
            .extracting(MultipartUpload::getUploadId).containsExactly(u.getUploadId());
        assertThat(uploads.findByStatusAndUpdatedAtBefore(UploadStatus.IN_PROGRESS, OffsetDateTime.now().minusMinutes(1)))
            .isEmpty();
        assertThat(uploads.countByStatus(UploadStatus.IN_PROGRESS)).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run (from `services/s3`): `mvn -B -q test -Dtest=MultipartRepositoryTest`
Expected: compilation failure — `MultipartUpload`, `UploadPart`, `UploadStatus`, `MultipartUploadRepository` not found.

- [ ] **Step 4: Write the migration**

`services/s3/src/main/resources/db/migration/V3__multipart_uploads.sql`:
```sql
CREATE TABLE multipart_uploads (
    upload_id    UUID PRIMARY KEY,
    bucket_name  TEXT NOT NULL REFERENCES buckets(name),
    key          TEXT NOT NULL,
    content_type TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'ABORTED')),
    etag         TEXT,
    initiated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX multipart_uploads_bucket_status ON multipart_uploads (bucket_name, status);
CREATE INDEX multipart_uploads_status_updated ON multipart_uploads (status, updated_at);

CREATE TABLE upload_parts (
    upload_id   UUID NOT NULL REFERENCES multipart_uploads(upload_id) ON DELETE CASCADE,
    part_number INT  NOT NULL CHECK (part_number BETWEEN 1 AND 10000),
    storage_id  UUID NOT NULL,
    size_bytes  BIGINT NOT NULL,
    etag        TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (upload_id, part_number)
);

ALTER TABLE objects ALTER COLUMN storage_id DROP NOT NULL;
ALTER TABLE objects ADD COLUMN upload_id UUID REFERENCES multipart_uploads(upload_id);
ALTER TABLE objects ADD CONSTRAINT objects_one_backing
    CHECK ((storage_id IS NULL) <> (upload_id IS NULL));
```

- [ ] **Step 5: Write the entities**

`domain/UploadStatus.java`:
```java
package dev.cloudlite.s3.domain;

public enum UploadStatus {
    IN_PROGRESS,
    COMPLETED,
    ABORTED
}
```

`domain/MultipartUpload.java`:
```java
package dev.cloudlite.s3.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "multipart_uploads")
public class MultipartUpload {

    @Id
    @Column(name = "upload_id")
    private UUID uploadId;

    @Column(name = "bucket_name", nullable = false)
    private String bucketName;

    // "key" is a JPQL reserved word, so the attribute is objectKey.
    @Column(name = "key", nullable = false)
    private String objectKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UploadStatus status;

    @Column
    private String etag;

    @Column(name = "initiated_at", nullable = false)
    private OffsetDateTime initiatedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected MultipartUpload() {
        // for JPA
    }

    public MultipartUpload(UUID uploadId, String bucketName, String objectKey, String contentType) {
        this.uploadId = uploadId;
        this.bucketName = bucketName;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.status = UploadStatus.IN_PROGRESS;
        this.initiatedAt = OffsetDateTime.now();
        this.updatedAt = this.initiatedAt;
    }

    public boolean belongsTo(String bucket, String key) {
        return bucketName.equals(bucket) && objectKey.equals(key);
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public void complete(String etag) {
        this.status = UploadStatus.COMPLETED;
        this.etag = etag;
        touch();
    }

    public void abort() {
        this.status = UploadStatus.ABORTED;
        touch();
    }

    public UUID getUploadId() { return uploadId; }
    public String getBucketName() { return bucketName; }
    public String getObjectKey() { return objectKey; }
    public String getContentType() { return contentType; }
    public UploadStatus getStatus() { return status; }
    public String getEtag() { return etag; }
    public OffsetDateTime getInitiatedAt() { return initiatedAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
```

`domain/UploadPartId.java`:
```java
package dev.cloudlite.s3.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UploadPartId implements Serializable {

    @Column(name = "upload_id")
    private UUID uploadId;

    @Column(name = "part_number")
    private int partNumber;

    protected UploadPartId() {
        // for JPA
    }

    public UploadPartId(UUID uploadId, int partNumber) {
        this.uploadId = uploadId;
        this.partNumber = partNumber;
    }

    public UUID getUploadId() { return uploadId; }
    public int getPartNumber() { return partNumber; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UploadPartId other)) return false;
        return partNumber == other.partNumber && Objects.equals(uploadId, other.uploadId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uploadId, partNumber);
    }
}
```

`domain/UploadPart.java`:
```java
package dev.cloudlite.s3.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "upload_parts")
public class UploadPart {

    @EmbeddedId
    private UploadPartId id;

    @Column(name = "storage_id", nullable = false)
    private UUID storageId;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String etag;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UploadPart() {
        // for JPA
    }

    public UploadPart(UUID uploadId, int partNumber, UUID storageId, long sizeBytes, String etag) {
        this.id = new UploadPartId(uploadId, partNumber);
        this.storageId = storageId;
        this.sizeBytes = sizeBytes;
        this.etag = etag;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getUploadId() { return id.getUploadId(); }
    public int getPartNumber() { return id.getPartNumber(); }
    public UUID getStorageId() { return storageId; }
    public long getSizeBytes() { return sizeBytes; }
    public String getEtag() { return etag; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
```

Modify `domain/ObjectMetadata.java`:
- change `@Column(name = "storage_id", nullable = false)` to `@Column(name = "storage_id")`;
- add after `storageId`:
```java
    @Column(name = "upload_id")
    private UUID uploadId;
```
- add after the existing constructor:
```java
    public static ObjectMetadata multipart(String bucketName, String key, String contentType,
                                           long sizeBytes, String etag, UUID uploadId) {
        ObjectMetadata m = new ObjectMetadata(bucketName, key, contentType, sizeBytes, etag, null);
        m.uploadId = uploadId;
        return m;
    }
```
- add getters:
```java
    public UUID getUploadId() {
        return uploadId;
    }

    public boolean isMultipart() {
        return uploadId != null;
    }
```

- [ ] **Step 6: Write the repositories**

`repository/MultipartUploadRepository.java`:
```java
package dev.cloudlite.s3.repository;

import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.domain.UploadStatus;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MultipartUploadRepository extends JpaRepository<MultipartUpload, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from MultipartUpload u where u.uploadId = :uploadId")
    Optional<MultipartUpload> findForUpdate(@Param("uploadId") UUID uploadId);

    List<MultipartUpload> findByBucketNameAndStatusOrderByObjectKeyAscInitiatedAtAsc(String bucketName, UploadStatus status);

    List<MultipartUpload> findByStatusAndUpdatedAtBefore(UploadStatus status, OffsetDateTime cutoff);

    boolean existsByBucketNameAndStatus(String bucketName, UploadStatus status);

    long countByStatus(UploadStatus status);

    @Query("select u.uploadId from MultipartUpload u"
        + " where u.status = dev.cloudlite.s3.domain.UploadStatus.COMPLETED"
        + " and not exists (select 1 from ObjectMetadata o where o.uploadId = u.uploadId)")
    List<UUID> findUnreferencedCompletedIds();

    // upload_parts rows go with ON DELETE CASCADE.
    @Transactional
    @Modifying
    @Query("delete from MultipartUpload u where u.bucketName = :bucketName"
        + " and u.status <> dev.cloudlite.s3.domain.UploadStatus.IN_PROGRESS")
    int deleteFinishedByBucketName(@Param("bucketName") String bucketName);
}
```

`repository/UploadPartRepository.java`:
```java
package dev.cloudlite.s3.repository;

import dev.cloudlite.s3.domain.UploadPart;
import dev.cloudlite.s3.domain.UploadPartId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UploadPartRepository extends JpaRepository<UploadPart, UploadPartId> {

    List<UploadPart> findByIdUploadIdOrderByIdPartNumberAsc(UUID uploadId);

    @Query("select p.storageId from UploadPart p")
    List<UUID> findAllStorageIds();
}
```

Modify `repository/ObjectRepository.java` — add:
```java
    @Query("select o.storageId from ObjectMetadata o where o.storageId is not null")
    List<UUID> findAllStorageIds();
```
(with imports `java.util.List`, `java.util.UUID`, `org.springframework.data.jpa.repository.Query`).

- [ ] **Step 7: Run the new test and the full suite**

Run: `mvn -B -q test -Dtest=MultipartRepositoryTest` — Expected: 6 tests pass.
Run: `mvn -B -q test` and the count command — Expected: `failures=0 errors=0` (Hibernate `ddl-auto: validate` passes against V3; existing tests unaffected).
If `deleteFinishedByBucketName` fails because the bulk delete leaves stale entities in the persistence context, add `clearAutomatically = true` to its `@Modifying`.

- [ ] **Step 8: Commit**

```bash
git add services/s3/src/main/resources/db/migration/V3__multipart_uploads.sql \
  services/s3/src/main/java/dev/cloudlite/s3/domain services/s3/src/main/java/dev/cloudlite/s3/repository \
  services/s3/src/test/java/dev/cloudlite/s3/repository/MultipartRepositoryTest.java
git commit -m "feat(s3): add multipart upload schema, entities and repositories"
```

---

### Task 2: Shared helpers and error codes

**Files:**
- Create: `util/Md5.java`, `controller/RequestBodies.java`
- Modify: `service/ObjectService.java` (use `Md5.hex`), `controller/ObjectController.java` (use `RequestBodies`), `error/S3ErrorCode.java`
- Test: `src/test/java/dev/cloudlite/s3/util/Md5Test.java`

**Interfaces:**
- Produces:
  - `Md5.hex(byte[]) → String` (lowercase hex); `Md5.multipartEtag(List<String> partMd5Hex) → String` (`<hex>-<n>`).
  - `RequestBodies.readBounded(HttpServletRequest, long maxBytes) → byte[]` (throws `S3ApiException(ENTITY_TOO_LARGE)`); `RequestBodies.contentTypeOrNull(String) → String` (null/blank → null; unparseable → `application/octet-stream`). Package-private in `dev.cloudlite.s3.controller`.
  - `S3ErrorCode.NO_SUCH_UPLOAD`, `INVALID_PART`, `INVALID_PART_ORDER`, `ENTITY_TOO_SMALL`, `MALFORMED_XML`.

- [ ] **Step 1: Write the failing test**

`services/s3/src/test/java/dev/cloudlite/s3/util/Md5Test.java`:
```java
package dev.cloudlite.s3.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class Md5Test {

    @Test
    void hexMatchesKnownDigest() {
        assertThat(Md5.hex("hello".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo("5d41402abc4b2a76b9719d911017c592");
    }

    @Test
    void multipartEtagIsMd5OfConcatenatedBinaryDigestsWithPartCount() {
        // md5("hello"), md5("world") -> md5(bin||bin) + "-2", computed independently with Python hashlib.
        String etag = Md5.multipartEtag(List.of(
            "5d41402abc4b2a76b9719d911017c592",
            "7d793037a0760186574b0282f2f435e7"));

        assertThat(etag).isEqualTo("065947336a2f2a95ba8899f3675c3be6-2");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -B -q test -Dtest=Md5Test` — Expected: compilation failure, `Md5` not found.

- [ ] **Step 3: Implement `Md5` and the error codes**

`util/Md5.java`:
```java
package dev.cloudlite.s3.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class Md5 {

    private Md5() {
    }

    public static String hex(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    // AWS multipart ETag: md5 of the concatenated binary part digests, then "-<part count>".
    public static String multipartEtag(List<String> partMd5Hex) {
        MessageDigest digest = digest();
        for (String hex : partMd5Hex) {
            digest.update(HexFormat.of().parseHex(hex));
        }
        return HexFormat.of().formatHex(digest.digest()) + "-" + partMd5Hex.size();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
```

In `service/ObjectService.java`: replace `md5Hex(body)` with `Md5.hex(body)`, delete the private `md5Hex` method and its now-unused imports (`MessageDigest`, `NoSuchAlgorithmException`, `HexFormat`), add `import dev.cloudlite.s3.util.Md5;`.

In `error/S3ErrorCode.java`, add before `INTERNAL_ERROR`:
```java
    NO_SUCH_UPLOAD("NoSuchUpload", HttpStatus.NOT_FOUND, "The specified multipart upload does not exist"),
    INVALID_PART("InvalidPart", HttpStatus.BAD_REQUEST, "One or more of the specified parts could not be found or its entity tag did not match"),
    INVALID_PART_ORDER("InvalidPartOrder", HttpStatus.BAD_REQUEST, "The list of parts was not in ascending order"),
    ENTITY_TOO_SMALL("EntityTooSmall", HttpStatus.BAD_REQUEST, "Your proposed upload is smaller than the minimum allowed object size"),
    MALFORMED_XML("MalformedXML", HttpStatus.BAD_REQUEST, "The XML you provided was not well-formed or did not validate"),
```

- [ ] **Step 4: Extract `RequestBodies`**

`controller/RequestBodies.java`:
```java
package dev.cloudlite.s3.controller;

import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

final class RequestBodies {

    private RequestBodies() {
    }

    static byte[] readBounded(HttpServletRequest request, long maxBytes) throws IOException {
        if (request.getContentLengthLong() > maxBytes) {
            throw new S3ApiException(S3ErrorCode.ENTITY_TOO_LARGE, "");
        }
        InputStream in = request.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new S3ApiException(S3ErrorCode.ENTITY_TOO_LARGE, "");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    static String contentTypeOrNull(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null; // services default null/blank to application/octet-stream
        }
        try {
            MediaType.parseMediaType(contentType);
            return contentType;
        } catch (InvalidMediaTypeException e) {
            return "application/octet-stream";
        }
    }
}
```

In `controller/ObjectController.java`, replace the body of `put` with:
```java
        byte[] body = RequestBodies.readBounded(request, objectService.maxObjectSize());
        String resolvedContentType = RequestBodies.contentTypeOrNull(contentType);
        String etag = objectService.put(bucket, stripLeadingSlash(key), body, resolvedContentType);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, "\"" + etag + "\"").build();
```
and delete the private `validateContentType` and `readBoundedBody` methods plus unused imports.

- [ ] **Step 5: Run tests**

Run: `mvn -B -q test` + count — Expected: `failures=0 errors=0` (Md5Test passes; existing ObjectController/ObjectService tests still pass, proving the extraction kept behaviour).

- [ ] **Step 6: Commit**

```bash
git add services/s3/src
git commit -m "refactor(s3): extract Md5 and request-body helpers, add multipart error codes"
```

---

### Task 3: `UploadCleaner` and `MultipartService`

**Files:**
- Create: `service/UploadCleaner.java`, `service/MultipartService.java`, `service/CompletedPart.java`, `service/CompletionResult.java`
- Test: `src/test/java/dev/cloudlite/s3/service/MultipartServiceTest.java`, `src/test/java/dev/cloudlite/s3/service/UploadCleanerTest.java`

**Interfaces:**
- Consumes: Task 1 entities/repositories; `Md5`; `S3ErrorCode` codes; `BlobStore.put/get/delete`; `BlobNotFoundException`.
- Produces:
  - `record CompletedPart(int partNumber, String etag)`; `record CompletionResult(String bucket, String key, String etag)`.
  - `UploadCleaner(MultipartUploadRepository, UploadPartRepository, BlobStore, TransactionOperations)`: `void deleteBlobsQuietly(Collection<UUID>)`; `void discardQuietly(UUID uploadId)` (deletes the upload row — parts cascade — then its blobs; logs and swallows errors).
  - `MultipartService(BucketRepository, MultipartUploadRepository, UploadPartRepository, ObjectRepository, BlobStore, UploadCleaner, TransactionOperations)`:
    - `static final long MIN_PART_SIZE = 5L * 1024 * 1024`, `static final int MAX_PART_NUMBER = 10_000`
    - `MultipartUpload create(String bucket, String key, String contentType)`
    - `String uploadPart(String bucket, String key, UUID uploadId, int partNumber, byte[] body)` → part md5 hex
    - `CompletionResult complete(String bucket, String key, UUID uploadId, List<CompletedPart> parts)`
    - `void abort(String bucket, String key, UUID uploadId)`
    - `List<UploadPart> listParts(String bucket, String key, UUID uploadId)`
    - `MultipartUpload requireInProgress(String bucket, String key, UUID uploadId)`
    - `List<MultipartUpload> listUploads(String bucket)`
    - `boolean expire(UUID uploadId, OffsetDateTime cutoff)` (for the reconciler)

- [ ] **Step 1: Write the failing tests**

`services/s3/src/test/java/dev/cloudlite/s3/service/MultipartServiceTest.java`:
```java
package dev.cloudlite.s3.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import dev.cloudlite.s3.domain.UploadPart;
import dev.cloudlite.s3.domain.UploadPartId;
import dev.cloudlite.s3.domain.UploadStatus;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.repository.BucketRepository;
import dev.cloudlite.s3.repository.MultipartUploadRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import dev.cloudlite.s3.repository.UploadPartRepository;
import dev.cloudlite.s3.storage.BlobStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

class MultipartServiceTest {

    private static final long MIB = 1024 * 1024;

    private BucketRepository buckets;
    private MultipartUploadRepository uploads;
    private UploadPartRepository parts;
    private ObjectRepository objects;
    private BlobStore store;
    private UploadCleaner cleaner;
    private MultipartService service;

    @BeforeEach
    void setUp() {
        buckets = mock(BucketRepository.class);
        uploads = mock(MultipartUploadRepository.class);
        parts = mock(UploadPartRepository.class);
        objects = mock(ObjectRepository.class);
        store = mock(BlobStore.class);
        cleaner = mock(UploadCleaner.class);
        service = new MultipartService(buckets, uploads, parts, objects, store, cleaner,
            TransactionOperations.withoutTransaction());
    }

    private MultipartUpload inProgress(String bucket, String key) {
        MultipartUpload u = new MultipartUpload(UUID.randomUUID(), bucket, key, "text/plain");
        when(uploads.findById(u.getUploadId())).thenReturn(Optional.of(u));
        when(uploads.findForUpdate(u.getUploadId())).thenReturn(Optional.of(u));
        return u;
    }

    private UploadPart part(UUID uploadId, int n, long size, String etag) {
        return new UploadPart(uploadId, n, UUID.randomUUID(), size, etag);
    }

    @Test
    void createRejectsUnknownBucket() {
        when(buckets.existsById("photos")).thenReturn(false);

        assertThatThrownBy(() -> service.create("photos", "big.bin", null))
            .isInstanceOf(S3ApiException.class)
            .extracting("errorCode").isEqualTo(S3ErrorCode.NO_SUCH_BUCKET);
    }

    @Test
    void createDefaultsContentTypeAndSavesInProgressUpload() {
        when(buckets.existsById("photos")).thenReturn(true);
        when(uploads.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MultipartUpload u = service.create("photos", "big.bin", " ");

        assertThat(u.getContentType()).isEqualTo("application/octet-stream");
        assertThat(u.getStatus()).isEqualTo(UploadStatus.IN_PROGRESS);
    }

    @Test
    void uploadPartRejectsPartNumberOutOfRange() {
        MultipartUpload u = inProgress("photos", "big.bin");

        assertThatThrownBy(() -> service.uploadPart("photos", "big.bin", u.getUploadId(), 0, new byte[1]))
            .extracting("errorCode").isEqualTo(S3ErrorCode.INVALID_ARGUMENT);
        assertThatThrownBy(() -> service.uploadPart("photos", "big.bin", u.getUploadId(), 10_001, new byte[1]))
            .extracting("errorCode").isEqualTo(S3ErrorCode.INVALID_ARGUMENT);
        verify(store, never()).put(any(), any());
    }

    @Test
    void uploadPartRejectsUploadForADifferentKey() {
        MultipartUpload u = inProgress("photos", "big.bin");

        assertThatThrownBy(() -> service.uploadPart("photos", "other.bin", u.getUploadId(), 1, new byte[1]))
            .extracting("errorCode").isEqualTo(S3ErrorCode.NO_SUCH_UPLOAD);
    }

    @Test
    void uploadPartStoresBlobSavesPartAndDeletesSupersededBlob() {
        MultipartUpload u = inProgress("photos", "big.bin");
        UploadPart old = part(u.getUploadId(), 1, 3, "old");
        when(parts.findById(new UploadPartId(u.getUploadId(), 1))).thenReturn(Optional.of(old));

        String etag = service.uploadPart("photos", "big.bin", u.getUploadId(), 1, "hello".getBytes());

        assertThat(etag).isEqualTo("5d41402abc4b2a76b9719d911017c592");
        ArgumentCaptor<UploadPart> saved = ArgumentCaptor.forClass(UploadPart.class);
        verify(parts).save(saved.capture());
        assertThat(saved.getValue().getSizeBytes()).isEqualTo(5);
        verify(store).put(any(), any());
        verify(cleaner).deleteBlobsQuietly(List.of(old.getStorageId()));
    }

    @Test
    void completeRejectsEmptyPartList() {
        MultipartUpload u = inProgress("photos", "big.bin");

        assertThatThrownBy(() -> service.complete("photos", "big.bin", u.getUploadId(), List.of()))
            .extracting("errorCode").isEqualTo(S3ErrorCode.MALFORMED_XML);
    }

    @Test
    void completeRejectsOutOfOrderParts() {
        MultipartUpload u = inProgress("photos", "big.bin");
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(u.getUploadId())).thenReturn(List.of(
            part(u.getUploadId(), 1, 5 * MIB, "a"), part(u.getUploadId(), 2, 1, "b")));

        assertThatThrownBy(() -> service.complete("photos", "big.bin", u.getUploadId(),
                List.of(new CompletedPart(2, "b"), new CompletedPart(1, "a"))))
            .extracting("errorCode").isEqualTo(S3ErrorCode.INVALID_PART_ORDER);
    }

    @Test
    void completeRejectsUnknownPartOrEtagMismatch() {
        MultipartUpload u = inProgress("photos", "big.bin");
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(u.getUploadId())).thenReturn(List.of(
            part(u.getUploadId(), 1, 5 * MIB, "a")));

        assertThatThrownBy(() -> service.complete("photos", "big.bin", u.getUploadId(),
                List.of(new CompletedPart(1, "WRONG"))))
            .extracting("errorCode").isEqualTo(S3ErrorCode.INVALID_PART);
        assertThatThrownBy(() -> service.complete("photos", "big.bin", u.getUploadId(),
                List.of(new CompletedPart(1, "a"), new CompletedPart(3, "c"))))
            .extracting("errorCode").isEqualTo(S3ErrorCode.INVALID_PART);
    }

    @Test
    void completeRejectsSmallNonLastPart() {
        MultipartUpload u = inProgress("photos", "big.bin");
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(u.getUploadId())).thenReturn(List.of(
            part(u.getUploadId(), 1, 5 * MIB - 1, "a"), part(u.getUploadId(), 2, 1, "b")));

        assertThatThrownBy(() -> service.complete("photos", "big.bin", u.getUploadId(),
                List.of(new CompletedPart(1, "a"), new CompletedPart(2, "b"))))
            .extracting("errorCode").isEqualTo(S3ErrorCode.ENTITY_TOO_SMALL);
    }

    @Test
    void completeSavesMultipartObjectDropsUnlistedPartsAndAcceptsQuotedEtags() {
        MultipartUpload u = inProgress("photos", "big.bin");
        UploadPart p1 = part(u.getUploadId(), 1, 5 * MIB, "5d41402abc4b2a76b9719d911017c592");
        UploadPart p2 = part(u.getUploadId(), 2, 7, "7d793037a0760186574b0282f2f435e7");
        UploadPart unlisted = part(u.getUploadId(), 3, 9, "cc");
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(u.getUploadId())).thenReturn(List.of(p1, p2, unlisted));
        when(objects.findById(new ObjectMetadataId("photos", "big.bin"))).thenReturn(Optional.empty());

        CompletionResult r = service.complete("photos", "big.bin", u.getUploadId(), List.of(
            new CompletedPart(1, "\"5d41402abc4b2a76b9719d911017c592\""),
            new CompletedPart(2, "7d793037a0760186574b0282f2f435e7")));

        assertThat(r.etag()).isEqualTo("065947336a2f2a95ba8899f3675c3be6-2");
        ArgumentCaptor<ObjectMetadata> saved = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(objects).save(saved.capture());
        assertThat(saved.getValue().isMultipart()).isTrue();
        assertThat(saved.getValue().getSizeBytes()).isEqualTo(5 * MIB + 7);
        assertThat(saved.getValue().getContentType()).isEqualTo("text/plain");
        verify(parts).delete(unlisted);
        assertThat(u.getStatus()).isEqualTo(UploadStatus.COMPLETED);
        verify(cleaner).deleteBlobsQuietly(List.of(unlisted.getStorageId()));
    }

    @Test
    void completeOverExistingMultipartObjectDiscardsPreviousUpload() {
        MultipartUpload u = inProgress("photos", "big.bin");
        UUID previousUpload = UUID.randomUUID();
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(u.getUploadId())).thenReturn(List.of(
            part(u.getUploadId(), 1, 1, "a")));
        when(objects.findById(new ObjectMetadataId("photos", "big.bin"))).thenReturn(Optional.of(
            ObjectMetadata.multipart("photos", "big.bin", "text/plain", 1, "x-1", previousUpload)));

        service.complete("photos", "big.bin", u.getUploadId(), List.of(new CompletedPart(1, "a")));

        verify(cleaner).discardQuietly(previousUpload);
    }

    @Test
    void completeOverExistingSingleBlobObjectDeletesThatBlob() {
        MultipartUpload u = inProgress("photos", "big.bin");
        UUID oldBlob = UUID.randomUUID();
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(u.getUploadId())).thenReturn(List.of(
            part(u.getUploadId(), 1, 1, "a")));
        when(objects.findById(new ObjectMetadataId("photos", "big.bin"))).thenReturn(Optional.of(
            new ObjectMetadata("photos", "big.bin", "text/plain", 1, "e", oldBlob)));

        service.complete("photos", "big.bin", u.getUploadId(), List.of(new CompletedPart(1, "a")));

        verify(cleaner).deleteBlobsQuietly(List.of(oldBlob));
    }

    @Test
    void completeRetryOnCompletedUploadReturnsStoredResultWithoutChanges() {
        MultipartUpload u = inProgress("photos", "big.bin");
        u.complete("abc-2");

        CompletionResult r = service.complete("photos", "big.bin", u.getUploadId(), List.of(new CompletedPart(1, "a")));

        assertThat(r.etag()).isEqualTo("abc-2");
        verify(objects, never()).save(any());
    }

    @Test
    void operationsOnAbortedUploadAreNoSuchUpload() {
        MultipartUpload u = inProgress("photos", "big.bin");
        u.abort();

        assertThatThrownBy(() -> service.complete("photos", "big.bin", u.getUploadId(), List.of(new CompletedPart(1, "a"))))
            .extracting("errorCode").isEqualTo(S3ErrorCode.NO_SUCH_UPLOAD);
        assertThatThrownBy(() -> service.abort("photos", "big.bin", u.getUploadId()))
            .extracting("errorCode").isEqualTo(S3ErrorCode.NO_SUCH_UPLOAD);
        assertThatThrownBy(() -> service.listParts("photos", "big.bin", u.getUploadId()))
            .extracting("errorCode").isEqualTo(S3ErrorCode.NO_SUCH_UPLOAD);
    }

    @Test
    void abortMarksAbortedDeletesPartRowsThenBlobs() {
        MultipartUpload u = inProgress("photos", "big.bin");
        UploadPart p1 = part(u.getUploadId(), 1, 1, "a");
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(u.getUploadId())).thenReturn(List.of(p1));

        service.abort("photos", "big.bin", u.getUploadId());

        assertThat(u.getStatus()).isEqualTo(UploadStatus.ABORTED);
        verify(parts).deleteAll(List.of(p1));
        verify(cleaner).deleteBlobsQuietly(List.of(p1.getStorageId()));
    }

    @Test
    void expireAbortsStaleInProgressUpload() {
        MultipartUpload u = inProgress("photos", "big.bin");

        boolean expired = service.expire(u.getUploadId(), OffsetDateTime.now().plusMinutes(1));

        assertThat(expired).isTrue();
        assertThat(u.getStatus()).isEqualTo(UploadStatus.ABORTED);
    }

    @Test
    void expireSkipsUploadTouchedAfterTheCutoff() {
        MultipartUpload u = inProgress("photos", "big.bin");

        boolean expired = service.expire(u.getUploadId(), OffsetDateTime.now().minusMinutes(1));

        assertThat(expired).isFalse();
        assertThat(u.getStatus()).isEqualTo(UploadStatus.IN_PROGRESS);
        verify(cleaner, never()).deleteBlobsQuietly(any());
    }
}
```

`services/s3/src/test/java/dev/cloudlite/s3/service/UploadCleanerTest.java`:
```java
package dev.cloudlite.s3.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cloudlite.s3.domain.UploadPart;
import dev.cloudlite.s3.repository.MultipartUploadRepository;
import dev.cloudlite.s3.repository.UploadPartRepository;
import dev.cloudlite.s3.storage.BlobNotFoundException;
import dev.cloudlite.s3.storage.BlobStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class UploadCleanerTest {

    private final MultipartUploadRepository uploads = mock(MultipartUploadRepository.class);
    private final UploadPartRepository parts = mock(UploadPartRepository.class);
    private final BlobStore store = mock(BlobStore.class);
    private final UploadCleaner cleaner =
        new UploadCleaner(uploads, parts, store, TransactionOperations.withoutTransaction());

    @Test
    void deleteBlobsQuietlyContinuesPastFailures() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        doThrow(new BlobNotFoundException("gone")).when(store).delete(a);
        doThrow(new RuntimeException("disk")).when(store).delete(b);

        cleaner.deleteBlobsQuietly(List.of(a, b, c));

        verify(store).delete(c);
    }

    @Test
    void discardQuietlyDeletesRowsThenBlobs() {
        UUID uploadId = UUID.randomUUID();
        UploadPart p = new UploadPart(uploadId, 1, UUID.randomUUID(), 1, "a");
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(uploadId)).thenReturn(List.of(p));

        cleaner.discardQuietly(uploadId);

        verify(parts).deleteAll(List.of(p));
        verify(uploads).deleteById(uploadId);
        verify(store).delete(p.getStorageId());
    }

    @Test
    void discardQuietlySwallowsDatabaseErrors() {
        UUID uploadId = UUID.randomUUID();
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(uploadId)).thenThrow(new RuntimeException("db down"));

        cleaner.discardQuietly(uploadId); // must not throw
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -B -q test -Dtest='MultipartServiceTest,UploadCleanerTest'` — Expected: compilation failure (`MultipartService`, `UploadCleaner`, `CompletedPart`, `CompletionResult` missing).

- [ ] **Step 3: Implement**

`service/CompletedPart.java`:
```java
package dev.cloudlite.s3.service;

public record CompletedPart(int partNumber, String etag) {
}
```

`service/CompletionResult.java`:
```java
package dev.cloudlite.s3.service;

public record CompletionResult(String bucket, String key, String etag) {
}
```

`service/UploadCleaner.java`:
```java
package dev.cloudlite.s3.service;

import dev.cloudlite.s3.domain.UploadPart;
import dev.cloudlite.s3.repository.MultipartUploadRepository;
import dev.cloudlite.s3.repository.UploadPartRepository;
import dev.cloudlite.s3.storage.BlobNotFoundException;
import dev.cloudlite.s3.storage.BlobStore;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

// Best-effort cleanup that runs after the transaction that unreferenced
// something has committed. Anything missed is swept by MultipartReconciler.
@Component
public class UploadCleaner {

    private static final Logger log = LoggerFactory.getLogger(UploadCleaner.class);

    private final MultipartUploadRepository uploads;
    private final UploadPartRepository parts;
    private final BlobStore store;
    private final TransactionOperations tx;

    public UploadCleaner(MultipartUploadRepository uploads, UploadPartRepository parts,
                         BlobStore store, TransactionOperations tx) {
        this.uploads = uploads;
        this.parts = parts;
        this.store = store;
        this.tx = tx;
    }

    public void deleteBlobsQuietly(Collection<UUID> storageIds) {
        for (UUID id : storageIds) {
            try {
                store.delete(id);
            } catch (BlobNotFoundException e) {
                // already gone
            } catch (RuntimeException e) {
                log.warn("s3: failed to delete blob {}, leaving it to the reconciler", id, e);
            }
        }
    }

    // Removes an upload nothing references any more (its object was
    // overwritten or deleted): rows first, then blobs.
    public void discardQuietly(UUID uploadId) {
        try {
            List<UUID> blobs = tx.execute(status -> {
                List<UploadPart> ps = parts.findByIdUploadIdOrderByIdPartNumberAsc(uploadId);
                parts.deleteAll(ps);
                uploads.deleteById(uploadId);
                return ps.stream().map(UploadPart::getStorageId).toList();
            });
            deleteBlobsQuietly(blobs == null ? List.of() : blobs);
        } catch (RuntimeException e) {
            log.warn("s3: failed to discard upload {}, leaving it to the reconciler", uploadId, e);
        }
    }
}
```

`service/MultipartService.java`:
```java
package dev.cloudlite.s3.service;

import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import dev.cloudlite.s3.domain.UploadPart;
import dev.cloudlite.s3.domain.UploadPartId;
import dev.cloudlite.s3.domain.UploadStatus;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.repository.BucketRepository;
import dev.cloudlite.s3.repository.MultipartUploadRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import dev.cloudlite.s3.repository.UploadPartRepository;
import dev.cloudlite.s3.storage.BlobStore;
import dev.cloudlite.s3.util.Md5;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class MultipartService {

    public static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    public static final int MAX_PART_NUMBER = 10_000;

    private final BucketRepository buckets;
    private final MultipartUploadRepository uploads;
    private final UploadPartRepository parts;
    private final ObjectRepository objects;
    private final BlobStore store;
    private final UploadCleaner cleaner;
    private final TransactionOperations tx;

    public MultipartService(BucketRepository buckets, MultipartUploadRepository uploads, UploadPartRepository parts,
                            ObjectRepository objects, BlobStore store, UploadCleaner cleaner,
                            TransactionOperations tx) {
        this.buckets = buckets;
        this.uploads = uploads;
        this.parts = parts;
        this.objects = objects;
        this.store = store;
        this.cleaner = cleaner;
        this.tx = tx;
    }

    public MultipartUpload create(String bucket, String key, String contentType) {
        if (!buckets.existsById(bucket)) {
            throw new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, bucket);
        }
        String resolved = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
        return uploads.save(new MultipartUpload(UUID.randomUUID(), bucket, key, resolved));
    }

    public String uploadPart(String bucket, String key, UUID uploadId, int partNumber, byte[] body) {
        if (partNumber < 1 || partNumber > MAX_PART_NUMBER) {
            throw new S3ApiException(S3ErrorCode.INVALID_ARGUMENT, "partNumber");
        }
        requireInProgress(bucket, key, uploadId); // fail fast before writing a blob

        String etag = Md5.hex(body);
        UUID storageId = UUID.randomUUID();
        store.put(storageId, new ByteArrayInputStream(body));

        UUID superseded = tx.execute(status -> {
            MultipartUpload upload = lockInProgress(bucket, key, uploadId);
            UUID previous = parts.findById(new UploadPartId(uploadId, partNumber))
                .map(UploadPart::getStorageId).orElse(null);
            parts.save(new UploadPart(uploadId, partNumber, storageId, body.length, etag));
            upload.touch();
            return previous;
        });
        if (superseded != null) {
            cleaner.deleteBlobsQuietly(List.of(superseded));
        }
        return etag;
    }

    public CompletionResult complete(String bucket, String key, UUID uploadId, List<CompletedPart> requested) {
        Completion c = tx.execute(status -> completeLocked(bucket, key, uploadId, requested));
        cleaner.deleteBlobsQuietly(c.garbageBlobs());
        c.supersededUpload().ifPresent(cleaner::discardQuietly);
        return c.result();
    }

    private Completion completeLocked(String bucket, String key, UUID uploadId, List<CompletedPart> requested) {
        MultipartUpload upload = uploads.findForUpdate(uploadId)
            .filter(u -> u.belongsTo(bucket, key))
            .orElseThrow(() -> noSuchUpload(uploadId));
        if (upload.getStatus() == UploadStatus.COMPLETED) {
            return new Completion(new CompletionResult(bucket, key, upload.getEtag()), List.of(), Optional.empty());
        }
        if (upload.getStatus() != UploadStatus.IN_PROGRESS) {
            throw noSuchUpload(uploadId);
        }
        if (requested.isEmpty()) {
            throw new S3ApiException(S3ErrorCode.MALFORMED_XML, "");
        }

        Map<Integer, UploadPart> stored = parts.findByIdUploadIdOrderByIdPartNumberAsc(uploadId).stream()
            .collect(Collectors.toMap(UploadPart::getPartNumber, Function.identity()));
        List<UploadPart> chosen = new ArrayList<>();
        int previous = 0;
        for (CompletedPart r : requested) {
            if (r.partNumber() <= previous) {
                throw new S3ApiException(S3ErrorCode.INVALID_PART_ORDER, "");
            }
            previous = r.partNumber();
            UploadPart p = stored.get(r.partNumber());
            if (p == null || !p.getEtag().equals(stripQuotes(r.etag()))) {
                throw new S3ApiException(S3ErrorCode.INVALID_PART, Integer.toString(r.partNumber()));
            }
            chosen.add(p);
        }
        for (int i = 0; i < chosen.size() - 1; i++) {
            if (chosen.get(i).getSizeBytes() < MIN_PART_SIZE) {
                throw new S3ApiException(S3ErrorCode.ENTITY_TOO_SMALL, Integer.toString(chosen.get(i).getPartNumber()));
            }
        }

        List<UUID> garbage = new ArrayList<>();
        Set<Integer> keep = new HashSet<>();
        chosen.forEach(p -> keep.add(p.getPartNumber()));
        for (UploadPart p : stored.values()) {
            if (!keep.contains(p.getPartNumber())) {
                garbage.add(p.getStorageId());
                parts.delete(p);
            }
        }

        UUID supersededUpload = null;
        Optional<ObjectMetadata> existing = objects.findById(new ObjectMetadataId(bucket, key));
        if (existing.isPresent()) {
            if (existing.get().isMultipart()) {
                supersededUpload = existing.get().getUploadId();
            } else {
                garbage.add(existing.get().getStorageId());
            }
        }

        String etag = Md5.multipartEtag(chosen.stream().map(UploadPart::getEtag).toList());
        long size = chosen.stream().mapToLong(UploadPart::getSizeBytes).sum();
        objects.save(ObjectMetadata.multipart(bucket, key, upload.getContentType(), size, etag, uploadId));
        upload.complete(etag);
        return new Completion(new CompletionResult(bucket, key, etag), garbage, Optional.ofNullable(supersededUpload));
    }

    public void abort(String bucket, String key, UUID uploadId) {
        List<UUID> blobs = tx.execute(status -> abortLocked(lockInProgress(bucket, key, uploadId)));
        cleaner.deleteBlobsQuietly(blobs);
    }

    public List<UploadPart> listParts(String bucket, String key, UUID uploadId) {
        requireInProgress(bucket, key, uploadId);
        return parts.findByIdUploadIdOrderByIdPartNumberAsc(uploadId);
    }

    public MultipartUpload requireInProgress(String bucket, String key, UUID uploadId) {
        return uploads.findById(uploadId)
            .filter(u -> u.belongsTo(bucket, key) && u.getStatus() == UploadStatus.IN_PROGRESS)
            .orElseThrow(() -> noSuchUpload(uploadId));
    }

    public List<MultipartUpload> listUploads(String bucket) {
        if (!buckets.existsById(bucket)) {
            throw new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, bucket);
        }
        return uploads.findByBucketNameAndStatusOrderByObjectKeyAscInitiatedAtAsc(bucket, UploadStatus.IN_PROGRESS);
    }

    // Reconciler entry point. The cutoff is re-checked under the row lock so a
    // part that landed after the reconciler's query keeps the upload alive.
    public boolean expire(UUID uploadId, OffsetDateTime cutoff) {
        List<UUID> blobs = tx.execute(status -> uploads.findForUpdate(uploadId)
            .filter(u -> u.getStatus() == UploadStatus.IN_PROGRESS && u.getUpdatedAt().isBefore(cutoff))
            .map(this::abortLocked)
            .orElse(null));
        if (blobs == null) {
            return false;
        }
        cleaner.deleteBlobsQuietly(blobs);
        return true;
    }

    private MultipartUpload lockInProgress(String bucket, String key, UUID uploadId) {
        return uploads.findForUpdate(uploadId)
            .filter(u -> u.belongsTo(bucket, key) && u.getStatus() == UploadStatus.IN_PROGRESS)
            .orElseThrow(() -> noSuchUpload(uploadId));
    }

    private List<UUID> abortLocked(MultipartUpload upload) {
        List<UploadPart> ps = parts.findByIdUploadIdOrderByIdPartNumberAsc(upload.getUploadId());
        parts.deleteAll(ps);
        upload.abort();
        return ps.stream().map(UploadPart::getStorageId).toList();
    }

    private static S3ApiException noSuchUpload(UUID uploadId) {
        return new S3ApiException(S3ErrorCode.NO_SUCH_UPLOAD, uploadId.toString());
    }

    private static String stripQuotes(String etag) {
        return etag == null ? "" : etag.replace("\"", "").trim();
    }

    private record Completion(CompletionResult result, List<UUID> garbageBlobs, Optional<UUID> supersededUpload) {
    }
}
```

Note: `S3ApiException` must expose `getErrorCode()` (the tests use `extracting("errorCode")`); it already does — `GlobalExceptionHandler` calls `ex.getErrorCode()`.

- [ ] **Step 4: Run tests**

Run: `mvn -B -q test -Dtest='MultipartServiceTest,UploadCleanerTest'` — Expected: all pass.
Run: `mvn -B -q test` + count — Expected: `failures=0 errors=0`.

- [ ] **Step 5: Commit**

```bash
git add services/s3/src
git commit -m "feat(s3): add multipart service with atomic, idempotent complete"
```

---

### Task 4: Prove complete is atomic against real Postgres

**Files:**
- Test: `src/test/java/dev/cloudlite/s3/service/MultipartCompleteAtomicityTest.java`

**Interfaces:**
- Consumes: `MultipartService`, `UploadCleaner`, repositories (Tasks 1, 3).

- [ ] **Step 1: Write the test**

`services/s3/src/test/java/dev/cloudlite/s3/service/MultipartCompleteAtomicityTest.java`:
```java
package dev.cloudlite.s3.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import dev.cloudlite.s3.domain.Bucket;
import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import dev.cloudlite.s3.domain.UploadPart;
import dev.cloudlite.s3.domain.UploadStatus;
import dev.cloudlite.s3.repository.BucketRepository;
import dev.cloudlite.s3.repository.MultipartUploadRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import dev.cloudlite.s3.repository.UploadPartRepository;
import dev.cloudlite.s3.storage.BlobStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// No test-managed transaction: the service's own transaction must commit or
// roll back for real, or the rollback assertion proves nothing.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(TransactionAutoConfiguration.class)
@Import({MultipartService.class, UploadCleaner.class})
class MultipartCompleteAtomicityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private MultipartService service;
    @Autowired private BucketRepository buckets;
    @Autowired private MultipartUploadRepository uploads;
    @Autowired private UploadPartRepository parts;
    @SpyBean private ObjectRepository objects;
    @MockBean private BlobStore store;

    @AfterEach
    void cleanUp() {
        reset(objects);
        objects.deleteAll();
        parts.deleteAll();
        uploads.deleteAll();
        buckets.deleteAll();
    }

    @Test
    void failureAfterObjectUpsertRollsBackEverything() {
        buckets.save(new Bucket("photos"));
        MultipartUpload upload = uploads.save(new MultipartUpload(UUID.randomUUID(), "photos", "big.bin", "text/plain"));
        parts.save(new UploadPart(upload.getUploadId(), 1, UUID.randomUUID(), 1, "a"));
        parts.save(new UploadPart(upload.getUploadId(), 2, UUID.randomUUID(), 1, "b")); // unlisted: deleted inside the tx
        doThrow(new IllegalStateException("simulated crash")).when(objects).save(any());

        assertThatThrownBy(() -> service.complete("photos", "big.bin", upload.getUploadId(),
                List.of(new CompletedPart(1, "a"))))
            .hasMessageContaining("simulated crash");

        reset(objects);
        assertThat(objects.findById(new ObjectMetadataId("photos", "big.bin"))).isEmpty();
        assertThat(uploads.findById(upload.getUploadId()).orElseThrow().getStatus()).isEqualTo(UploadStatus.IN_PROGRESS);
        assertThat(parts.findByIdUploadIdOrderByIdPartNumberAsc(upload.getUploadId()))
            .extracting(UploadPart::getPartNumber).containsExactly(1, 2);
    }

    @Test
    void successfulCompleteCommitsObjectAndStatusTogether() {
        buckets.save(new Bucket("photos"));
        MultipartUpload upload = uploads.save(new MultipartUpload(UUID.randomUUID(), "photos", "big.bin", "text/plain"));
        parts.save(new UploadPart(upload.getUploadId(), 1, UUID.randomUUID(), 1, "a"));

        service.complete("photos", "big.bin", upload.getUploadId(), List.of(new CompletedPart(1, "a")));

        assertThat(objects.findById(new ObjectMetadataId("photos", "big.bin")).orElseThrow().getUploadId())
            .isEqualTo(upload.getUploadId());
        assertThat(uploads.findById(upload.getUploadId()).orElseThrow().getStatus()).isEqualTo(UploadStatus.COMPLETED);
    }
}
```

- [ ] **Step 2: Watch it fail first, then pass**

This test exercises code that already exists (Task 3), so to prove it can fail: temporarily replace `tx.execute(status -> completeLocked(...))` in `MultipartService.complete` with a direct call `completeLocked(...)` wrapped as `Completion c = completeLocked(bucket, key, uploadId, requested);`.
Run: `mvn -B -q test -Dtest=MultipartCompleteAtomicityTest` — Expected: `failureAfterObjectUpsertRollsBackEverything` FAILS (part 2 is gone — the delete was not rolled back).
Restore the `tx.execute(...)` call. Run again — Expected: both tests pass.
If `@SpyBean` on a Spring Data repository proxy fails to stub `save`, use `@SpyBean(proxyTargetAware = true)` (Boot 3.3 default is already true; this note is for older defaults).

- [ ] **Step 3: Full suite and commit**

Run: `mvn -B -q test` + count — Expected: `failures=0 errors=0`.
```bash
git add services/s3/src/test/java/dev/cloudlite/s3/service/MultipartCompleteAtomicityTest.java
git commit -m "test(s3): prove multipart complete rolls back atomically on failure"
```

---

### Task 5: Multipart-aware object reads, deletes, overwrites, and bucket delete

**Files:**
- Modify: `service/ObjectService.java`, `service/BucketService.java`
- Test: `src/test/java/dev/cloudlite/s3/service/ObjectServiceTest.java`, `src/test/java/dev/cloudlite/s3/service/BucketServiceTest.java`

**Interfaces:**
- Consumes: `UploadPartRepository`, `UploadCleaner`, `MultipartUploadRepository`, `ObjectMetadata.isMultipart/getUploadId`.
- Produces: `ObjectService(BucketRepository, ObjectRepository, UploadPartRepository, BlobStore, UploadCleaner)`; `BucketService(BucketRepository, ObjectRepository, MultipartUploadRepository)`. `ObjectService.getBlob(ObjectMetadata)` returns one stream over all parts for multipart objects.

- [ ] **Step 1: Update constructors in existing tests, add failing tests**

In `ObjectServiceTest.setUp()`: add fields `private UploadPartRepository parts; private UploadCleaner cleaner;`, initialise with `mock(...)`, and construct `new ObjectService(buckets, objects, parts, store, cleaner)`. Add imports `dev.cloudlite.s3.repository.UploadPartRepository`, `dev.cloudlite.s3.domain.UploadPart`, `java.io.ByteArrayInputStream`, `java.io.InputStream`, `java.util.List`. Append:
```java
    @Test
    void getBlobOfMultipartObjectStreamsPartsInOrder() throws Exception {
        UUID uploadId = UUID.randomUUID();
        UploadPart p1 = new UploadPart(uploadId, 1, UUID.randomUUID(), 3, "a");
        UploadPart p2 = new UploadPart(uploadId, 2, UUID.randomUUID(), 3, "b");
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(uploadId)).thenReturn(List.of(p1, p2));
        when(store.get(p1.getStorageId())).thenReturn(new ByteArrayInputStream("abc".getBytes()));
        when(store.get(p2.getStorageId())).thenReturn(new ByteArrayInputStream("def".getBytes()));

        try (InputStream in = service.getBlob(
                ObjectMetadata.multipart("photos", "big.bin", "text/plain", 6, "e-2", uploadId))) {
            assertThat(new String(in.readAllBytes())).isEqualTo("abcdef");
        }
    }

    @Test
    void getBlobOpensPartsLazily() {
        UUID uploadId = UUID.randomUUID();
        UploadPart p1 = new UploadPart(uploadId, 1, UUID.randomUUID(), 3, "a");
        UploadPart p2 = new UploadPart(uploadId, 2, UUID.randomUUID(), 3, "b");
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(uploadId)).thenReturn(List.of(p1, p2));
        when(store.get(p1.getStorageId())).thenReturn(new ByteArrayInputStream("abc".getBytes()));

        service.getBlob(ObjectMetadata.multipart("photos", "big.bin", "text/plain", 6, "e-2", uploadId));

        verify(store, never()).get(p2.getStorageId());
    }

    @Test
    void putOverMultipartObjectDiscardsItsUpload() {
        UUID uploadId = UUID.randomUUID();
        when(buckets.existsById("photos")).thenReturn(true);
        when(objects.findById(new ObjectMetadataId("photos", "big.bin"))).thenReturn(Optional.of(
            ObjectMetadata.multipart("photos", "big.bin", "text/plain", 6, "e-2", uploadId)));

        service.put("photos", "big.bin", "hi".getBytes(), "text/plain");

        verify(cleaner).discardQuietly(uploadId);
        verify(store, never()).delete(any());
    }

    @Test
    void deleteMultipartObjectDiscardsItsUpload() {
        UUID uploadId = UUID.randomUUID();
        when(objects.findById(new ObjectMetadataId("photos", "big.bin"))).thenReturn(Optional.of(
            ObjectMetadata.multipart("photos", "big.bin", "text/plain", 6, "e-2", uploadId)));

        service.delete("photos", "big.bin");

        verify(objects).deleteById(new ObjectMetadataId("photos", "big.bin"));
        verify(cleaner).discardQuietly(uploadId);
    }
```

In `BucketServiceTest`: add `private MultipartUploadRepository uploads = mock(...)` (match the file's existing field style), construct `new BucketService(buckets, objects, uploads)`, and append:
```java
    @Test
    void deleteRejectsWhileUploadsAreInProgress() {
        when(buckets.existsById("photos")).thenReturn(true);
        when(uploads.existsByBucketNameAndStatus("photos", UploadStatus.IN_PROGRESS)).thenReturn(true);

        assertThatThrownBy(() -> service.delete("photos"))
            .isInstanceOf(S3ApiException.class)
            .extracting("errorCode").isEqualTo(S3ErrorCode.BUCKET_NOT_EMPTY);
        verify(buckets, never()).deleteById("photos");
    }

    @Test
    void deletePurgesFinishedUploadRowsFirst() {
        when(buckets.existsById("photos")).thenReturn(true);

        service.delete("photos");

        InOrder order = inOrder(uploads, buckets);
        order.verify(uploads).deleteFinishedByBucketName("photos");
        order.verify(buckets).deleteById("photos");
    }
```
(imports: `MultipartUploadRepository`, `UploadStatus`, `org.mockito.InOrder`, `static org.mockito.Mockito.inOrder` as needed.)

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B -q test -Dtest='ObjectServiceTest,BucketServiceTest'` — Expected: compilation failure (constructor arity).

- [ ] **Step 3: Implement**

`ObjectService`:
- fields + constructor:
```java
    private final BucketRepository buckets;
    private final ObjectRepository objects;
    private final UploadPartRepository parts;
    private final BlobStore store;
    private final UploadCleaner cleaner;

    public ObjectService(BucketRepository buckets, ObjectRepository objects, UploadPartRepository parts,
                         BlobStore store, UploadCleaner cleaner) {
        this.buckets = buckets;
        this.objects = objects;
        this.parts = parts;
        this.store = store;
        this.cleaner = cleaner;
    }
```
- in `put`, replace the `existing.ifPresent(old -> { ... })` block with `existing.ifPresent(this::discardBacking);`
- replace `getBlob`:
```java
    public InputStream getBlob(ObjectMetadata metadata) {
        if (!metadata.isMultipart()) {
            return store.get(metadata.getStorageId());
        }
        Iterator<UploadPart> remaining = parts.findByIdUploadIdOrderByIdPartNumberAsc(metadata.getUploadId()).iterator();
        // Opens each part only when the previous one is exhausted, so at most one file is open.
        return new SequenceInputStream(new Enumeration<>() {
            @Override
            public boolean hasMoreElements() {
                return remaining.hasNext();
            }

            @Override
            public InputStream nextElement() {
                return store.get(remaining.next().getStorageId());
            }
        });
    }
```
- replace the tail of `delete` (after `objects.deleteById(id);`) with `discardBacking(existing.get());`
- add:
```java
    private void discardBacking(ObjectMetadata old) {
        if (old.isMultipart()) {
            cleaner.discardQuietly(old.getUploadId());
            return;
        }
        try {
            store.delete(old.getStorageId());
        } catch (RuntimeException e) {
            log.warn("s3: object {}/{}: failed to delete superseded blob {}, leaving it to the reconciler",
                old.getBucketName(), old.getKey(), old.getStorageId(), e);
        }
    }
```
- imports: `java.io.SequenceInputStream`, `java.util.Enumeration`, `java.util.Iterator`, `dev.cloudlite.s3.domain.UploadPart`, `dev.cloudlite.s3.repository.UploadPartRepository`.

`BucketService`:
- add `MultipartUploadRepository uploads` field/constructor parameter (third).
- in `delete`, after the existing objects/existence checks and before `buckets.deleteById(name)`:
```java
        if (uploads.existsByBucketNameAndStatus(name, UploadStatus.IN_PROGRESS)) {
            throw new S3ApiException(S3ErrorCode.BUCKET_NOT_EMPTY, name);
        }
        uploads.deleteFinishedByBucketName(name);
```

- [ ] **Step 4: Run tests**

Run: `mvn -B -q test` + count — Expected: `failures=0 errors=0`. (Existing ObjectServiceTest assertions about deleting the superseded blob still pass via `discardBacking`.)

- [ ] **Step 5: Commit**

```bash
git add services/s3/src
git commit -m "feat(s3): serve, overwrite and delete multipart objects; guard bucket delete"
```

---

### Task 6: XML DTOs, request parser, `MultipartController`, routing guards

**Files:**
- Create: `dto/InitiateMultipartUploadResultXml.java`, `dto/CompleteMultipartUploadXml.java`, `dto/CompletedPartXml.java`, `dto/CompleteMultipartUploadResultXml.java`, `dto/ListPartsResultXml.java`, `dto/PartXml.java`, `dto/ListMultipartUploadsResultXml.java`, `dto/UploadXml.java`, `controller/CompleteRequestParser.java`, `controller/MultipartController.java`
- Modify: `controller/ObjectController.java` (PUT params guard)
- Test: `src/test/java/dev/cloudlite/s3/controller/CompleteRequestParserTest.java`, `src/test/java/dev/cloudlite/s3/controller/MultipartControllerTest.java`

**Interfaces:**
- Consumes: `MultipartService` (Task 3), `ObjectService.maxObjectSize()`, `RequestBodies`.
- Produces: `CompleteRequestParser.parse(byte[]) → List<CompletedPart>` (throws `S3ApiException(MALFORMED_XML)`); HTTP endpoints per Global Constraints.

- [ ] **Step 1: Write the failing tests**

`CompleteRequestParserTest.java`:
```java
package dev.cloudlite.s3.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.service.CompletedPart;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CompleteRequestParserTest {

    private static byte[] xml(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesPartsWithAwsNamespaceAndQuotedEtags() {
        var parts = CompleteRequestParser.parse(xml(
            "<CompleteMultipartUpload xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Part><PartNumber>1</PartNumber><ETag>\"aa\"</ETag></Part>"
                + "<Part><PartNumber>2</PartNumber><ETag>bb</ETag></Part>"
                + "</CompleteMultipartUpload>"));

        assertThat(parts).containsExactly(new CompletedPart(1, "\"aa\""), new CompletedPart(2, "bb"));
    }

    @Test
    void parsesASinglePart() {
        var parts = CompleteRequestParser.parse(xml(
            "<CompleteMultipartUpload><Part><PartNumber>7</PartNumber><ETag>x</ETag></Part></CompleteMultipartUpload>"));

        assertThat(parts).containsExactly(new CompletedPart(7, "x"));
    }

    @Test
    void rejectsEmptyBodyNonXmlNoPartsAndIncompleteParts() {
        for (String body : new String[] {
            "",
            "not xml",
            "<CompleteMultipartUpload></CompleteMultipartUpload>",
            "<CompleteMultipartUpload><Part><ETag>x</ETag></Part></CompleteMultipartUpload>",
            "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber></Part></CompleteMultipartUpload>",
            "<CompleteMultipartUpload><Part><PartNumber>abc</PartNumber><ETag>x</ETag></Part></CompleteMultipartUpload>"
        }) {
            assertThatThrownBy(() -> CompleteRequestParser.parse(xml(body)))
                .as("body: %s", body)
                .extracting("errorCode").isEqualTo(S3ErrorCode.MALFORMED_XML);
        }
    }
}
```

`MultipartControllerTest.java`:
```java
package dev.cloudlite.s3.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.domain.UploadPart;
import dev.cloudlite.s3.error.GlobalExceptionHandler;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.iamclient.AuthInterceptor;
import dev.cloudlite.s3.iamclient.AuthWebMvcConfigurer;
import dev.cloudlite.s3.service.CompletedPart;
import dev.cloudlite.s3.service.CompletionResult;
import dev.cloudlite.s3.service.MultipartService;
import dev.cloudlite.s3.service.ObjectService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = {MultipartController.class, ObjectController.class},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {AuthInterceptor.class, AuthWebMvcConfigurer.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class MultipartControllerTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired private MockMvc mockMvc;
    @MockBean private MultipartService multipartService;
    @MockBean private ObjectService objectService;

    @Test
    void createReturnsInitiateResult() throws Exception {
        given(multipartService.create("photos", "dir/big.bin", "video/mp4"))
            .willReturn(new MultipartUpload(ID, "photos", "dir/big.bin", "video/mp4"));

        mockMvc.perform(post("/photos/dir/big.bin?uploads").header("Content-Type", "video/mp4"))
            .andExpect(status().isOk())
            .andExpect(xpath("/InitiateMultipartUploadResult/Bucket").string("photos"))
            .andExpect(xpath("/InitiateMultipartUploadResult/Key").string("dir/big.bin"))
            .andExpect(xpath("/InitiateMultipartUploadResult/UploadId").string(ID.toString()));
    }

    @Test
    void uploadPartReturnsQuotedEtagAndDoesNotTouchPlainPut() throws Exception {
        given(objectService.maxObjectSize()).willReturn(100L * 1024 * 1024);
        given(multipartService.uploadPart(eq("photos"), eq("big.bin"), eq(ID), eq(3), any())).willReturn("abc");

        mockMvc.perform(put("/photos/big.bin?partNumber=3&uploadId=" + ID).content("hi".getBytes()))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"abc\""));
        verify(objectService, never()).put(any(), any(), any(), any());
    }

    @Test
    void uploadPartWithBadPartNumberIsInvalidArgument() throws Exception {
        mockMvc.perform(put("/photos/big.bin?partNumber=abc&uploadId=" + ID).content("hi".getBytes()))
            .andExpect(status().isBadRequest())
            .andExpect(xpath("/Error/Code").string("InvalidArgument"));
    }

    @Test
    void badUploadIdIsNoSuchUpload() throws Exception {
        mockMvc.perform(delete("/photos/big.bin?uploadId=not-a-uuid"))
            .andExpect(status().isNotFound())
            .andExpect(xpath("/Error/Code").string("NoSuchUpload"));
    }

    @Test
    void putWithOnlyPartNumberIsInvalidArgument() throws Exception {
        mockMvc.perform(put("/photos/big.bin?partNumber=1").content("hi".getBytes()))
            .andExpect(status().isBadRequest())
            .andExpect(xpath("/Error/Code").string("InvalidArgument"));
        verify(objectService, never()).put(any(), any(), any(), any());
    }

    @Test
    void putWithOnlyUploadIdIsInvalidArgument() throws Exception {
        mockMvc.perform(put("/photos/big.bin?uploadId=" + ID).content("hi".getBytes()))
            .andExpect(status().isBadRequest())
            .andExpect(xpath("/Error/Code").string("InvalidArgument"));
        verify(objectService, never()).put(any(), any(), any(), any());
    }

    @Test
    void completeParsesBodyAndReturnsResult() throws Exception {
        given(multipartService.complete("photos", "big.bin", ID,
                List.of(new CompletedPart(1, "\"a\""), new CompletedPart(2, "\"b\""))))
            .willReturn(new CompletionResult("photos", "big.bin", "e-2"));

        mockMvc.perform(post("/photos/big.bin?uploadId=" + ID)
                .contentType("application/x-www-form-urlencoded")
                .content("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>\"a\"</ETag></Part>"
                    + "<Part><PartNumber>2</PartNumber><ETag>\"b\"</ETag></Part></CompleteMultipartUpload>"))
            .andExpect(status().isOk())
            .andExpect(xpath("/CompleteMultipartUploadResult/Location").string("/photos/big.bin"))
            .andExpect(xpath("/CompleteMultipartUploadResult/ETag").string("\"e-2\""));
    }

    @Test
    void completeWithMalformedBodyIsMalformedXml() throws Exception {
        mockMvc.perform(post("/photos/big.bin?uploadId=" + ID).content("nope"))
            .andExpect(status().isBadRequest())
            .andExpect(xpath("/Error/Code").string("MalformedXML"));
    }

    @Test
    void abortReturns204() throws Exception {
        mockMvc.perform(delete("/photos/big.bin?uploadId=" + ID))
            .andExpect(status().isNoContent());
        verify(multipartService).abort("photos", "big.bin", ID);
        verify(objectService, never()).delete(any(), any());
    }

    @Test
    void listPartsReturnsParts() throws Exception {
        given(multipartService.listParts("photos", "big.bin", ID))
            .willReturn(List.of(new UploadPart(ID, 1, UUID.randomUUID(), 5, "aa")));

        mockMvc.perform(get("/photos/big.bin?uploadId=" + ID))
            .andExpect(status().isOk())
            .andExpect(xpath("/ListPartsResult/UploadId").string(ID.toString()))
            .andExpect(xpath("/ListPartsResult/Part[1]/PartNumber").string("1"))
            .andExpect(xpath("/ListPartsResult/Part[1]/ETag").string("\"aa\""))
            .andExpect(xpath("/ListPartsResult/Part[1]/Size").string("5"));
    }

    @Test
    void listUploadsReturnsInProgressUploads() throws Exception {
        given(multipartService.listUploads("photos"))
            .willReturn(List.of(new MultipartUpload(ID, "photos", "big.bin", "text/plain")));

        mockMvc.perform(get("/photos?uploads"))
            .andExpect(status().isOk())
            .andExpect(xpath("/ListMultipartUploadsResult/Bucket").string("photos"))
            .andExpect(xpath("/ListMultipartUploadsResult/Upload[1]/Key").string("big.bin"))
            .andExpect(xpath("/ListMultipartUploadsResult/Upload[1]/UploadId").string(ID.toString()));
    }

    @Test
    void serviceErrorsMapToS3Errors() throws Exception {
        given(multipartService.listParts("photos", "big.bin", ID))
            .willThrow(new S3ApiException(S3ErrorCode.NO_SUCH_UPLOAD, ID.toString()));

        mockMvc.perform(get("/photos/big.bin?uploadId=" + ID))
            .andExpect(status().isNotFound())
            .andExpect(xpath("/Error/Code").string("NoSuchUpload"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B -q test -Dtest='CompleteRequestParserTest,MultipartControllerTest'` — Expected: compilation failure.

- [ ] **Step 3: Implement DTOs**

`dto/InitiateMultipartUploadResultXml.java`:
```java
package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "InitiateMultipartUploadResult")
public class InitiateMultipartUploadResultXml {

    @JacksonXmlProperty(localName = "Bucket")
    private final String bucket;

    @JacksonXmlProperty(localName = "Key")
    private final String key;

    @JacksonXmlProperty(localName = "UploadId")
    private final String uploadId;

    public InitiateMultipartUploadResultXml(String bucket, String key, String uploadId) {
        this.bucket = bucket;
        this.key = key;
        this.uploadId = uploadId;
    }

    public String getBucket() { return bucket; }
    public String getKey() { return key; }
    public String getUploadId() { return uploadId; }
}
```

`dto/CompletedPartXml.java`:
```java
package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CompletedPartXml {

    @JacksonXmlProperty(localName = "PartNumber")
    private Integer partNumber;

    @JacksonXmlProperty(localName = "ETag")
    private String etag;

    public Integer getPartNumber() { return partNumber; }
    public void setPartNumber(Integer partNumber) { this.partNumber = partNumber; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
}
```

`dto/CompleteMultipartUploadXml.java`:
```java
package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

@JacksonXmlRootElement(localName = "CompleteMultipartUpload")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompleteMultipartUploadXml {

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "Part")
    private List<CompletedPartXml> parts;

    public List<CompletedPartXml> getParts() { return parts; }
    public void setParts(List<CompletedPartXml> parts) { this.parts = parts; }
}
```

`dto/CompleteMultipartUploadResultXml.java`:
```java
package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "CompleteMultipartUploadResult")
public class CompleteMultipartUploadResultXml {

    @JacksonXmlProperty(localName = "Location")
    private final String location;

    @JacksonXmlProperty(localName = "Bucket")
    private final String bucket;

    @JacksonXmlProperty(localName = "Key")
    private final String key;

    @JacksonXmlProperty(localName = "ETag")
    private final String etag;

    public CompleteMultipartUploadResultXml(String location, String bucket, String key, String etag) {
        this.location = location;
        this.bucket = bucket;
        this.key = key;
        this.etag = etag;
    }

    public String getLocation() { return location; }
    public String getBucket() { return bucket; }
    public String getKey() { return key; }
    public String getEtag() { return etag; }
}
```

`dto/PartXml.java`:
```java
package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.time.OffsetDateTime;

public class PartXml {

    @JacksonXmlProperty(localName = "PartNumber")
    private final int partNumber;

    @JacksonXmlProperty(localName = "ETag")
    private final String etag;

    @JacksonXmlProperty(localName = "Size")
    private final long size;

    @JacksonXmlProperty(localName = "LastModified")
    private final OffsetDateTime lastModified;

    public PartXml(int partNumber, String etag, long size, OffsetDateTime lastModified) {
        this.partNumber = partNumber;
        this.etag = etag;
        this.size = size;
        this.lastModified = lastModified;
    }

    public int getPartNumber() { return partNumber; }
    public String getEtag() { return etag; }
    public long getSize() { return size; }
    public OffsetDateTime getLastModified() { return lastModified; }
}
```

`dto/ListPartsResultXml.java`:
```java
package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

@JacksonXmlRootElement(localName = "ListPartsResult")
public class ListPartsResultXml {

    @JacksonXmlProperty(localName = "Bucket")
    private final String bucket;

    @JacksonXmlProperty(localName = "Key")
    private final String key;

    @JacksonXmlProperty(localName = "UploadId")
    private final String uploadId;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "Part")
    private final List<PartXml> parts;

    public ListPartsResultXml(String bucket, String key, String uploadId, List<PartXml> parts) {
        this.bucket = bucket;
        this.key = key;
        this.uploadId = uploadId;
        this.parts = parts;
    }

    public String getBucket() { return bucket; }
    public String getKey() { return key; }
    public String getUploadId() { return uploadId; }
    public List<PartXml> getParts() { return parts; }
}
```

`dto/UploadXml.java`:
```java
package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.time.OffsetDateTime;

public class UploadXml {

    @JacksonXmlProperty(localName = "Key")
    private final String key;

    @JacksonXmlProperty(localName = "UploadId")
    private final String uploadId;

    @JacksonXmlProperty(localName = "Initiated")
    private final OffsetDateTime initiated;

    public UploadXml(String key, String uploadId, OffsetDateTime initiated) {
        this.key = key;
        this.uploadId = uploadId;
        this.initiated = initiated;
    }

    public String getKey() { return key; }
    public String getUploadId() { return uploadId; }
    public OffsetDateTime getInitiated() { return initiated; }
}
```

`dto/ListMultipartUploadsResultXml.java`:
```java
package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

@JacksonXmlRootElement(localName = "ListMultipartUploadsResult")
public class ListMultipartUploadsResultXml {

    @JacksonXmlProperty(localName = "Bucket")
    private final String bucket;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "Upload")
    private final List<UploadXml> uploads;

    public ListMultipartUploadsResultXml(String bucket, List<UploadXml> uploads) {
        this.bucket = bucket;
        this.uploads = uploads;
    }

    public String getBucket() { return bucket; }
    public List<UploadXml> getUploads() { return uploads; }
}
```

- [ ] **Step 4: Implement the parser and controller**

`controller/CompleteRequestParser.java`:
```java
package dev.cloudlite.s3.controller;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import dev.cloudlite.s3.dto.CompleteMultipartUploadXml;
import dev.cloudlite.s3.dto.CompletedPartXml;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.service.CompletedPart;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Parses the CompleteMultipartUpload body from raw bytes, whatever the
// request's Content-Type (curl -d sends application/x-www-form-urlencoded).
final class CompleteRequestParser {

    private static final XmlMapper XML = new XmlMapper();

    private CompleteRequestParser() {
    }

    static List<CompletedPart> parse(byte[] body) {
        CompleteMultipartUploadXml parsed;
        try {
            parsed = XML.readValue(body, CompleteMultipartUploadXml.class);
        } catch (IOException e) {
            throw malformed();
        }
        if (parsed == null || parsed.getParts() == null || parsed.getParts().isEmpty()) {
            throw malformed();
        }
        List<CompletedPart> parts = new ArrayList<>();
        for (CompletedPartXml p : parsed.getParts()) {
            if (p == null || p.getPartNumber() == null || p.getEtag() == null) {
                throw malformed();
            }
            parts.add(new CompletedPart(p.getPartNumber(), p.getEtag()));
        }
        return parts;
    }

    private static S3ApiException malformed() {
        return new S3ApiException(S3ErrorCode.MALFORMED_XML, "");
    }
}
```

`controller/MultipartController.java`:
```java
package dev.cloudlite.s3.controller;

import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.dto.CompleteMultipartUploadResultXml;
import dev.cloudlite.s3.dto.InitiateMultipartUploadResultXml;
import dev.cloudlite.s3.dto.ListMultipartUploadsResultXml;
import dev.cloudlite.s3.dto.ListPartsResultXml;
import dev.cloudlite.s3.dto.PartXml;
import dev.cloudlite.s3.dto.UploadXml;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.service.CompletionResult;
import dev.cloudlite.s3.service.MultipartService;
import dev.cloudlite.s3.service.ObjectService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Multipart routes are distinguished from plain object routes by query
// parameters; Spring prefers the mapping with more matching params.
@RestController
public class MultipartController {

    private static final long MAX_COMPLETE_BODY = 2L * 1024 * 1024;

    private final MultipartService multipart;
    private final ObjectService objectService;

    public MultipartController(MultipartService multipart, ObjectService objectService) {
        this.multipart = multipart;
        this.objectService = objectService;
    }

    @PostMapping(path = "/{bucket}/{*key}", params = "uploads")
    public ResponseEntity<InitiateMultipartUploadResultXml> create(
            @PathVariable String bucket,
            @PathVariable String key,
            @RequestHeader(value = "Content-Type", required = false) String contentType) {
        MultipartUpload upload = multipart.create(bucket, strip(key), RequestBodies.contentTypeOrNull(contentType));
        return xml(new InitiateMultipartUploadResultXml(bucket, upload.getObjectKey(), upload.getUploadId().toString()));
    }

    @PutMapping(path = "/{bucket}/{*key}", params = {"partNumber", "uploadId"})
    public ResponseEntity<Void> uploadPart(
            @PathVariable String bucket,
            @PathVariable String key,
            @RequestParam("partNumber") String partNumber,
            @RequestParam("uploadId") String uploadId,
            HttpServletRequest request) throws IOException {
        int number = parsePartNumber(partNumber);
        UUID id = parseUploadId(uploadId);
        byte[] body = RequestBodies.readBounded(request, objectService.maxObjectSize());
        String etag = multipart.uploadPart(bucket, strip(key), id, number, body);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, "\"" + etag + "\"").build();
    }

    // A PUT with only one of the two parameters must never fall through to a
    // plain object PUT (which would overwrite the whole object).
    @PutMapping(path = "/{bucket}/{*key}", params = {"partNumber", "!uploadId"})
    public ResponseEntity<Void> partNumberWithoutUploadId() {
        throw new S3ApiException(S3ErrorCode.INVALID_ARGUMENT, "uploadId");
    }

    @PutMapping(path = "/{bucket}/{*key}", params = {"uploadId", "!partNumber"})
    public ResponseEntity<Void> uploadIdWithoutPartNumber() {
        throw new S3ApiException(S3ErrorCode.INVALID_ARGUMENT, "partNumber");
    }

    @PostMapping(path = "/{bucket}/{*key}", params = "uploadId")
    public ResponseEntity<CompleteMultipartUploadResultXml> complete(
            @PathVariable String bucket,
            @PathVariable String key,
            @RequestParam("uploadId") String uploadId,
            HttpServletRequest request) throws IOException {
        UUID id = parseUploadId(uploadId);
        byte[] body = RequestBodies.readBounded(request, MAX_COMPLETE_BODY);
        String k = strip(key);
        CompletionResult result = multipart.complete(bucket, k, id, CompleteRequestParser.parse(body));
        return xml(new CompleteMultipartUploadResultXml("/" + bucket + "/" + k, bucket, k, "\"" + result.etag() + "\""));
    }

    @DeleteMapping(path = "/{bucket}/{*key}", params = "uploadId")
    public ResponseEntity<Void> abort(
            @PathVariable String bucket, @PathVariable String key, @RequestParam("uploadId") String uploadId) {
        multipart.abort(bucket, strip(key), parseUploadId(uploadId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping(path = "/{bucket}/{*key}", params = "uploadId")
    public ResponseEntity<ListPartsResultXml> listParts(
            @PathVariable String bucket, @PathVariable String key, @RequestParam("uploadId") String uploadId) {
        UUID id = parseUploadId(uploadId);
        String k = strip(key);
        var parts = multipart.listParts(bucket, k, id).stream()
            .map(p -> new PartXml(p.getPartNumber(), "\"" + p.getEtag() + "\"", p.getSizeBytes(), p.getUpdatedAt()))
            .toList();
        return xml(new ListPartsResultXml(bucket, k, id.toString(), parts));
    }

    @GetMapping(path = "/{bucket}", params = "uploads")
    public ResponseEntity<ListMultipartUploadsResultXml> listUploads(@PathVariable String bucket) {
        var uploads = multipart.listUploads(bucket).stream()
            .map(u -> new UploadXml(u.getObjectKey(), u.getUploadId().toString(), u.getInitiatedAt()))
            .toList();
        return xml(new ListMultipartUploadsResultXml(bucket, uploads));
    }

    private static <T> ResponseEntity<T> xml(T body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(body);
    }

    private static String strip(String key) {
        return key.startsWith("/") ? key.substring(1) : key;
    }

    private static int parsePartNumber(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new S3ApiException(S3ErrorCode.INVALID_ARGUMENT, "partNumber");
        }
    }

    // AWS answers NoSuchUpload for an upload ID it can't parse.
    private static UUID parseUploadId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new S3ApiException(S3ErrorCode.NO_SUCH_UPLOAD, raw);
        }
    }
}
```

In `ObjectController.java`, change `@PutMapping("/{bucket}/{*key}")` to:
```java
    @PutMapping(path = "/{bucket}/{*key}", params = {"!partNumber", "!uploadId"})
```

- [ ] **Step 5: Run tests**

Run: `mvn -B -q test -Dtest='CompleteRequestParserTest,MultipartControllerTest,ObjectControllerTest'` — Expected: all pass.
If Spring reports an ambiguous mapping between `ObjectController.get` and `MultipartController.listParts` (or `delete`/`abort`), add `params = "!uploadId"` to `ObjectController`'s `@GetMapping` and `@DeleteMapping` (spec open item 3) and re-run.
Run: `mvn -B -q test` + count — Expected: `failures=0 errors=0` **except** `AuthInterceptorTest` may fail to start its context (it lists controllers explicitly and lacks a `MultipartService` bean only if `MultipartController` is scanned — `@WebMvcTest(controllers=…)` limits scanning, so it should still pass; if not, Task 7 fixes it).

- [ ] **Step 6: Commit**

```bash
git add services/s3/src
git commit -m "feat(s3): add multipart REST endpoints with query-param routing"
```

---

### Task 7: IAM action mapping in `AuthInterceptor`

**Files:**
- Modify: `iamclient/AuthInterceptor.java`
- Test: `src/test/java/dev/cloudlite/s3/iamclient/AuthInterceptorTest.java`

**Interfaces:**
- Consumes: `MultipartController` routes (Task 6).

- [ ] **Step 1: Write the failing tests**

In `AuthInterceptorTest`: change `@WebMvcTest(controllers = {BucketController.class, ObjectController.class, HealthController.class})` to also include `MultipartController.class`; add `@MockBean private MultipartService multipartService;`; add imports `dev.cloudlite.s3.controller.MultipartController`, `dev.cloudlite.s3.service.MultipartService`, `dev.cloudlite.s3.domain.MultipartUpload`, `static ...MockMvcRequestBuilders.post`. Append:
```java
    private static final String UPLOAD_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void createMultipartUploadIsPutObject() throws Exception {
        given(multipartService.create(any(), any(), any()))
            .willReturn(new MultipartUpload(UUID.fromString(UPLOAD_ID), "photos", "big.bin", "text/plain"));

        mockMvc.perform(post("/photos/big.bin?uploads").header("Authorization", "Bearer good-token"))
            .andExpect(status().isOk());

        verify(iamClient).authorize("Bearer good-token", "s3:PutObject", "arn:cloudlite:s3:::photos/big.bin");
    }

    @Test
    void uploadPartIsPutObject() throws Exception {
        given(objectService.maxObjectSize()).willReturn(1024L);

        mockMvc.perform(put("/photos/big.bin?partNumber=1&uploadId=" + UPLOAD_ID)
                .header("Authorization", "Bearer good-token").content("x".getBytes()))
            .andExpect(status().isOk());

        verify(iamClient).authorize("Bearer good-token", "s3:PutObject", "arn:cloudlite:s3:::photos/big.bin");
    }

    @Test
    void completeIsPutObject() throws Exception {
        given(multipartService.complete(any(), any(), any(), any()))
            .willReturn(new dev.cloudlite.s3.service.CompletionResult("photos", "big.bin", "e-1"));

        mockMvc.perform(post("/photos/big.bin?uploadId=" + UPLOAD_ID)
                .header("Authorization", "Bearer good-token")
                .content("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>a</ETag></Part></CompleteMultipartUpload>"))
            .andExpect(status().isOk());

        verify(iamClient).authorize("Bearer good-token", "s3:PutObject", "arn:cloudlite:s3:::photos/big.bin");
    }

    @Test
    void abortIsAbortMultipartUpload() throws Exception {
        mockMvc.perform(delete("/photos/big.bin?uploadId=" + UPLOAD_ID).header("Authorization", "Bearer good-token"))
            .andExpect(status().isNoContent());

        verify(iamClient).authorize("Bearer good-token", "s3:AbortMultipartUpload", "arn:cloudlite:s3:::photos/big.bin");
    }

    @Test
    void listPartsIsListMultipartUploadParts() throws Exception {
        mockMvc.perform(get("/photos/big.bin?uploadId=" + UPLOAD_ID).header("Authorization", "Bearer good-token"))
            .andExpect(status().isOk());

        verify(iamClient).authorize("Bearer good-token", "s3:ListMultipartUploadParts", "arn:cloudlite:s3:::photos/big.bin");
    }

    @Test
    void listUploadsIsListBucketMultipartUploadsOnTheBucket() throws Exception {
        mockMvc.perform(get("/photos?uploads").header("Authorization", "Bearer good-token"))
            .andExpect(status().isOk());

        verify(iamClient).authorize("Bearer good-token", "s3:ListBucketMultipartUploads", "arn:cloudlite:s3:::photos");
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B -q test -Dtest=AuthInterceptorTest` — Expected: the new tests FAIL with 403 (the interceptor denies `POST` and maps `GET`/`DELETE` to the plain object actions).

- [ ] **Step 3: Implement**

In `AuthInterceptor.preHandle`, after `String method = request.getMethod();` add:
```java
        boolean uploadsParam = request.getParameter("uploads") != null;
        boolean uploadIdParam = request.getParameter("uploadId") != null;
```
Replace the bucket-level switch with:
```java
            action = switch (method) {
                case "PUT" -> "s3:CreateBucket";
                case "HEAD" -> "s3:ListBucket";
                case "DELETE" -> "s3:DeleteBucket";
                case "GET" -> {
                    if (uploadsParam) {
                        yield "s3:ListBucketMultipartUploads";
                    }
                    throw new IamAccessDeniedException();
                }
                default -> throw new IamAccessDeniedException();
            };
```
Replace the object-level switch with:
```java
            action = switch (method) {
                case "PUT" -> "s3:PutObject";
                case "GET" -> uploadIdParam ? "s3:ListMultipartUploadParts" : "s3:GetObject";
                case "HEAD" -> "s3:GetObject";
                case "DELETE" -> uploadIdParam ? "s3:AbortMultipartUpload" : "s3:DeleteObject";
                case "POST" -> {
                    if (uploadsParam || uploadIdParam) {
                        yield "s3:PutObject";
                    }
                    throw new IamAccessDeniedException();
                }
                default -> throw new IamAccessDeniedException();
            };
```

- [ ] **Step 4: Run tests**

Run: `mvn -B -q test` + count — Expected: `failures=0 errors=0`.

- [ ] **Step 5: Commit**

```bash
git add services/s3/src
git commit -m "feat(s3): map multipart requests to IAM actions"
```

---

### Task 8: BlobStore listing for garbage collection

**Files:**
- Create: `storage/BlobEntry.java`
- Modify: `storage/BlobStore.java`, `storage/DiskBlobStore.java`
- Test: `src/test/java/dev/cloudlite/s3/storage/DiskBlobStoreTest.java`

**Interfaces:**
- Produces: `record BlobEntry(String fileName, UUID blobId, boolean temp, Instant lastModified)` (`blobId` null unless the name is a canonical UUID); `BlobStore.entries() → List<BlobEntry>`; `BlobStore.deleteEntry(String fileName)` (refuses names resolving outside the data dir).

- [ ] **Step 1: Write the failing tests**

Append to `DiskBlobStoreTest` (it already has a `@TempDir` field — reuse its name; shown here as `dir`):
```java
    @Test
    void entriesClassifyBlobsTempFilesAndStrangers() throws Exception {
        DiskBlobStore store = new DiskBlobStore(dir.toString());
        UUID id = UUID.randomUUID();
        store.put(id, new java.io.ByteArrayInputStream("x".getBytes()));
        java.nio.file.Files.writeString(dir.resolve(UUID.randomUUID() + "." + UUID.randomUUID() + ".tmp"), "t");
        java.nio.file.Files.writeString(dir.resolve("README"), "r");

        var entries = store.entries();

        assertThat(entries).hasSize(3);
        assertThat(entries).filteredOn(e -> id.equals(e.blobId())).singleElement()
            .satisfies(e -> assertThat(e.temp()).isFalse());
        assertThat(entries).filteredOn(BlobEntry::temp).singleElement()
            .satisfies(e -> assertThat(e.blobId()).isNull());
        assertThat(entries).filteredOn(e -> e.fileName().equals("README")).singleElement()
            .satisfies(e -> assertThat(e.blobId()).isNull());
    }

    @Test
    void deleteEntryRemovesTheFileAndRefusesPathsOutsideTheDataDir() throws Exception {
        DiskBlobStore store = new DiskBlobStore(dir.toString());
        java.nio.file.Files.writeString(dir.resolve("a.tmp"), "t");

        store.deleteEntry("a.tmp");

        assertThat(java.nio.file.Files.exists(dir.resolve("a.tmp"))).isFalse();
        assertThatThrownBy(() -> store.deleteEntry("../escape"))
            .isInstanceOf(IllegalArgumentException.class);
    }
```
(Use the test file's existing `@TempDir` field name and assertion imports; add `import static org.assertj.core.api.Assertions.assertThatThrownBy;` if missing.)

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B -q test -Dtest=DiskBlobStoreTest` — Expected: compilation failure (`entries`, `BlobEntry`).

- [ ] **Step 3: Implement**

`storage/BlobEntry.java`:
```java
package dev.cloudlite.s3.storage;

import java.time.Instant;
import java.util.UUID;

// A file in the blob directory, as the reconciler sees it.
public record BlobEntry(String fileName, UUID blobId, boolean temp, Instant lastModified) {
}
```

`storage/BlobStore.java` — add:
```java
    List<BlobEntry> entries();
    void deleteEntry(String fileName);
```
(import `java.util.List`).

`storage/DiskBlobStore.java` — add (imports `java.time.Instant`, `java.util.List`, `java.util.stream.Stream`):
```java
    @Override
    public List<BlobEntry> entries() {
        try (Stream<Path> files = Files.list(dataDir)) {
            return files.filter(Files::isRegularFile).map(this::entryFor).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("storage: list " + dataDir, e);
        }
    }

    private BlobEntry entryFor(Path file) {
        String name = file.getFileName().toString();
        Instant modified;
        try {
            modified = Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            modified = Instant.now(); // unreadable mtime: treat as fresh so GC never deletes it
        }
        boolean temp = name.endsWith(".tmp");
        UUID id = null;
        if (!temp) {
            try {
                UUID parsed = UUID.fromString(name);
                id = parsed.toString().equals(name) ? parsed : null;
            } catch (IllegalArgumentException e) {
                id = null;
            }
        }
        return new BlobEntry(name, id, temp, modified);
    }

    @Override
    public void deleteEntry(String fileName) {
        Path target = dataDir.resolve(fileName).normalize();
        if (!dataDir.toAbsolutePath().normalize().equals(target.toAbsolutePath().getParent())) {
            throw new IllegalArgumentException("storage: refusing to delete outside the data dir: " + fileName);
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("storage: delete entry " + fileName, e);
        }
    }
```

- [ ] **Step 4: Run tests and commit**

Run: `mvn -B -q test` + count — Expected: `failures=0 errors=0`.
```bash
git add services/s3/src
git commit -m "feat(s3): list and delete raw blob-store entries for garbage collection"
```

---

### Task 9: `MultipartReconciler`, scheduling, metrics, config

**Files:**
- Create: `reconcile/ReconcileReport.java`, `reconcile/MultipartReconciler.java`, `reconcile/ReconcileSchedulingConfig.java`, `config/ClockConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/dev/cloudlite/s3/reconcile/MultipartReconcilerTest.java`

**Interfaces:**
- Consumes: `MultipartService.expire`, `UploadCleaner.discardQuietly`, repositories, `BlobStore.entries/deleteEntry`.
- Produces: `MultipartReconciler(MultipartUploadRepository, UploadPartRepository, ObjectRepository, MultipartService, UploadCleaner, BlobStore, MeterRegistry, Clock, Duration ttl, Duration grace)`; `ReconcileReport runOnce()`; `record ReconcileReport(int expired, int pruned, int blobsDeleted, List<String> failedSteps)`.

- [ ] **Step 1: Write the failing test**

`services/s3/src/test/java/dev/cloudlite/s3/reconcile/MultipartReconcilerTest.java`:
```java
package dev.cloudlite.s3.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.domain.UploadStatus;
import dev.cloudlite.s3.repository.MultipartUploadRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import dev.cloudlite.s3.repository.UploadPartRepository;
import dev.cloudlite.s3.service.MultipartService;
import dev.cloudlite.s3.service.UploadCleaner;
import dev.cloudlite.s3.storage.BlobEntry;
import dev.cloudlite.s3.storage.BlobStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MultipartReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private MultipartUploadRepository uploads;
    private UploadPartRepository parts;
    private ObjectRepository objects;
    private MultipartService multipart;
    private UploadCleaner cleaner;
    private BlobStore store;
    private SimpleMeterRegistry registry;
    private MultipartReconciler reconciler;

    @BeforeEach
    void setUp() {
        uploads = mock(MultipartUploadRepository.class);
        parts = mock(UploadPartRepository.class);
        objects = mock(ObjectRepository.class);
        multipart = mock(MultipartService.class);
        cleaner = mock(UploadCleaner.class);
        store = mock(BlobStore.class);
        registry = new SimpleMeterRegistry();
        reconciler = new MultipartReconciler(uploads, parts, objects, multipart, cleaner, store, registry,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24), Duration.ofHours(1));
    }

    @Test
    void metricsAreRegisteredAtZeroBeforeAnyRun() {
        assertThat(registry.get("s3.reconciler.uploads.expired").counter().count()).isZero();
        assertThat(registry.get("s3.reconciler.blobs.deleted").counter().count()).isZero();
        assertThat(registry.get("s3.reconciler.runs").tag("outcome", "ok").counter().count()).isZero();
        assertThat(registry.get("s3.reconciler.runs").tag("outcome", "partial").counter().count()).isZero();
        assertThat(registry.find("s3.multipart.uploads.in_progress").gauge()).isNotNull();
    }

    @Test
    void expiresStaleInProgressUploadsWithTheTtlCutoff() {
        MultipartUpload stale = new MultipartUpload(UUID.randomUUID(), "photos", "a", "text/plain");
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(24);
        when(uploads.findByStatusAndUpdatedAtBefore(UploadStatus.IN_PROGRESS, cutoff)).thenReturn(List.of(stale));
        when(multipart.expire(stale.getUploadId(), cutoff)).thenReturn(true);

        ReconcileReport report = reconciler.runOnce();

        assertThat(report.expired()).isEqualTo(1);
        assertThat(registry.get("s3.reconciler.uploads.expired").counter().count()).isEqualTo(1);
    }

    @Test
    void prunesUnreferencedCompletedAndOldAbortedUploads() {
        UUID completed = UUID.randomUUID();
        MultipartUpload aborted = new MultipartUpload(UUID.randomUUID(), "photos", "b", "text/plain");
        when(uploads.findUnreferencedCompletedIds()).thenReturn(List.of(completed));
        when(uploads.findByStatusAndUpdatedAtBefore(eq(UploadStatus.ABORTED), any())).thenReturn(List.of(aborted));

        ReconcileReport report = reconciler.runOnce();

        assertThat(report.pruned()).isEqualTo(2);
        verify(cleaner).discardQuietly(completed);
        verify(cleaner).discardQuietly(aborted.getUploadId());
    }

    @Test
    void garbageCollectsOnlyOldUnreferencedBlobsAndOldTempFiles() {
        UUID liveObject = UUID.randomUUID();
        UUID livePart = UUID.randomUUID();
        UUID oldOrphan = UUID.randomUUID();
        UUID freshOrphan = UUID.randomUUID();
        Instant old = NOW.minus(Duration.ofHours(2));
        Instant fresh = NOW.minus(Duration.ofMinutes(5));
        when(objects.findAllStorageIds()).thenReturn(List.of(liveObject));
        when(parts.findAllStorageIds()).thenReturn(List.of(livePart));
        when(store.entries()).thenReturn(List.of(
            new BlobEntry(liveObject.toString(), liveObject, false, old),
            new BlobEntry(livePart.toString(), livePart, false, old),
            new BlobEntry(oldOrphan.toString(), oldOrphan, false, old),
            new BlobEntry(freshOrphan.toString(), freshOrphan, false, fresh),
            new BlobEntry("x.y.tmp", null, true, old),
            new BlobEntry("z.w.tmp", null, true, fresh),
            new BlobEntry("README", null, false, old)));

        ReconcileReport report = reconciler.runOnce();

        assertThat(report.blobsDeleted()).isEqualTo(2);
        verify(store).deleteEntry(oldOrphan.toString());
        verify(store).deleteEntry("x.y.tmp");
        verify(store, never()).deleteEntry(liveObject.toString());
        verify(store, never()).deleteEntry(livePart.toString());
        verify(store, never()).deleteEntry(freshOrphan.toString());
        verify(store, never()).deleteEntry("z.w.tmp");
        verify(store, never()).deleteEntry("README");
    }

    @Test
    void aFailingStepDoesNotStopTheOthers() {
        when(uploads.findByStatusAndUpdatedAtBefore(eq(UploadStatus.IN_PROGRESS), any()))
            .thenThrow(new RuntimeException("db hiccup"));
        UUID orphan = UUID.randomUUID();
        when(store.entries()).thenReturn(List.of(
            new BlobEntry(orphan.toString(), orphan, false, NOW.minus(Duration.ofHours(2)))));

        ReconcileReport report = reconciler.runOnce();

        assertThat(report.failedSteps()).containsExactly("expire");
        verify(store).deleteEntry(orphan.toString());
        assertThat(registry.get("s3.reconciler.runs").tag("outcome", "partial").counter().count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -B -q test -Dtest=MultipartReconcilerTest` — Expected: compilation failure.

- [ ] **Step 3: Implement**

`reconcile/ReconcileReport.java`:
```java
package dev.cloudlite.s3.reconcile;

import java.util.List;

public record ReconcileReport(int expired, int pruned, int blobsDeleted, List<String> failedSteps) {
}
```

`reconcile/MultipartReconciler.java`:
```java
package dev.cloudlite.s3.reconcile;

import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.domain.UploadStatus;
import dev.cloudlite.s3.repository.MultipartUploadRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import dev.cloudlite.s3.repository.UploadPartRepository;
import dev.cloudlite.s3.service.MultipartService;
import dev.cloudlite.s3.service.UploadCleaner;
import dev.cloudlite.s3.storage.BlobEntry;
import dev.cloudlite.s3.storage.BlobStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Sweeps what best-effort cleanup misses: abandoned uploads, upload rows
// nothing references, and blob files no row points at. Assumes one S3 replica.
@Component
public class MultipartReconciler {

    private static final Logger log = LoggerFactory.getLogger(MultipartReconciler.class);

    private final MultipartUploadRepository uploads;
    private final UploadPartRepository parts;
    private final ObjectRepository objects;
    private final MultipartService multipart;
    private final UploadCleaner cleaner;
    private final BlobStore store;
    private final Clock clock;
    private final Duration ttl;
    private final Duration grace;
    private final Counter expiredCounter;
    private final Counter blobsDeletedCounter;
    private final Counter runsOk;
    private final Counter runsPartial;

    public MultipartReconciler(MultipartUploadRepository uploads, UploadPartRepository parts, ObjectRepository objects,
                               MultipartService multipart, UploadCleaner cleaner, BlobStore store,
                               MeterRegistry registry, Clock clock,
                               @Value("${s3.multipart.ttl:24h}") Duration ttl,
                               @Value("${s3.reconcile.grace:1h}") Duration grace) {
        this.uploads = uploads;
        this.parts = parts;
        this.objects = objects;
        this.multipart = multipart;
        this.cleaner = cleaner;
        this.store = store;
        this.clock = clock;
        this.ttl = ttl;
        this.grace = grace;
        // Registered up front so Prometheus sees a 0 sample before the first increment.
        this.expiredCounter = Counter.builder("s3.reconciler.uploads.expired").register(registry);
        this.blobsDeletedCounter = Counter.builder("s3.reconciler.blobs.deleted").register(registry);
        this.runsOk = Counter.builder("s3.reconciler.runs").tag("outcome", "ok").register(registry);
        this.runsPartial = Counter.builder("s3.reconciler.runs").tag("outcome", "partial").register(registry);
        Gauge.builder("s3.multipart.uploads.in_progress", uploads, r -> r.countByStatus(UploadStatus.IN_PROGRESS))
            .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runOnce();
    }

    public ReconcileReport runOnce() {
        List<String> failed = new ArrayList<>();
        int expired = step("expire", this::expireAbandoned, failed);
        int pruned = step("prune", this::pruneUploadRows, failed);
        int deleted = step("gc", this::collectGarbage, failed);
        (failed.isEmpty() ? runsOk : runsPartial).increment();
        log.info("s3: reconciler run: expired={} pruned={} blobsDeleted={} failedSteps={}", expired, pruned, deleted, failed);
        return new ReconcileReport(expired, pruned, deleted, List.copyOf(failed));
    }

    private int step(String name, IntSupplier body, List<String> failed) {
        try {
            return body.getAsInt();
        } catch (RuntimeException e) {
            log.warn("s3: reconciler step {} failed", name, e);
            failed.add(name);
            return 0;
        }
    }

    private int expireAbandoned() {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(clock.instant(), clock.getZone()).minus(ttl);
        int n = 0;
        for (MultipartUpload u : uploads.findByStatusAndUpdatedAtBefore(UploadStatus.IN_PROGRESS, cutoff)) {
            if (multipart.expire(u.getUploadId(), cutoff)) {
                log.debug("s3: reconciler expired upload {}", u.getUploadId());
                n++;
            }
        }
        expiredCounter.increment(n);
        return n;
    }

    private int pruneUploadRows() {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(clock.instant(), clock.getZone()).minus(ttl);
        List<UUID> ids = new ArrayList<>(uploads.findUnreferencedCompletedIds());
        uploads.findByStatusAndUpdatedAtBefore(UploadStatus.ABORTED, cutoff).forEach(u -> ids.add(u.getUploadId()));
        ids.forEach(cleaner::discardQuietly);
        return ids.size();
    }

    private int collectGarbage() {
        // Live set is read before listing files; anything written after is
        // younger than the grace period and left alone.
        Set<UUID> live = new HashSet<>(objects.findAllStorageIds());
        live.addAll(parts.findAllStorageIds());
        Instant cutoff = clock.instant().minus(grace);
        int n = 0;
        for (BlobEntry e : store.entries()) {
            if (!e.lastModified().isBefore(cutoff)) {
                continue;
            }
            boolean garbage = e.temp() || (e.blobId() != null && !live.contains(e.blobId()));
            if (!garbage) {
                continue;
            }
            try {
                store.deleteEntry(e.fileName());
                log.debug("s3: reconciler deleted {}", e.fileName());
                n++;
            } catch (RuntimeException ex) {
                log.warn("s3: reconciler could not delete {}", e.fileName(), ex);
            }
        }
        blobsDeletedCounter.increment(n);
        return n;
    }
}
```

`reconcile/ReconcileSchedulingConfig.java`:
```java
package dev.cloudlite.s3.reconcile;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

// Configured programmatically so the interval binds as a Duration ("10m").
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "s3.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class ReconcileSchedulingConfig implements SchedulingConfigurer {

    private final MultipartReconciler reconciler;
    private final Duration interval;

    public ReconcileSchedulingConfig(MultipartReconciler reconciler,
                                     @Value("${s3.reconcile.interval:10m}") Duration interval) {
        this.reconciler = reconciler;
        this.interval = interval;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(reconciler::runOnce, interval, interval));
    }
}
```

`config/ClockConfig.java`:
```java
package dev.cloudlite.s3.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

`application.yml` — extend the `s3:` block:
```yaml
s3:
  data-dir: ${S3_DATA_DIR:/data}
  multipart:
    ttl: ${S3_MULTIPART_TTL:24h}
  reconcile:
    interval: ${S3_RECONCILE_INTERVAL:10m}
    grace: ${S3_RECONCILE_GRACE:1h}
```

`reconciler::runOnce` returns a value; `FixedDelayTask` takes a `Runnable`, so if the method reference doesn't compile use `() -> reconciler.runOnce()`.

- [ ] **Step 4: Run tests**

Run: `mvn -B -q test` + count — Expected: `failures=0 errors=0`. `S3ApplicationIntegrationTest` now also starts the reconciler on `ApplicationReadyEvent` against its temp data dir; with a 1h grace it deletes nothing.

- [ ] **Step 5: Commit**

```bash
git add services/s3/src
git commit -m "feat(s3): add reconciler for abandoned uploads and orphaned blobs"
```

---

### Task 10: End-to-end integration tests

**Files:**
- Modify: `src/test/java/dev/cloudlite/s3/S3ApplicationIntegrationTest.java`

**Interfaces:**
- Consumes: everything above, through HTTP.

- [ ] **Step 1: Add the tests**

Append to `S3ApplicationIntegrationTest` (it already autowires a `TestRestTemplate restTemplate` whose interceptor adds the bearer token, and an IAM stub answering `ALLOW`; reuse them; add imports `java.util.Arrays`, `org.springframework.http.HttpEntity`, `org.springframework.http.HttpMethod`, `org.springframework.http.HttpHeaders`, `java.util.regex.Matcher`, `java.util.regex.Pattern`):
```java
    private static String xmlValue(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private String startUpload(String bucket, String key) {
        ResponseEntity<String> r = restTemplate.postForEntity("/" + bucket + "/" + key + "?uploads", null, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return xmlValue(r.getBody(), "UploadId");
    }

    private String putPart(String bucket, String key, String uploadId, int n, byte[] body) {
        ResponseEntity<Void> r = restTemplate.exchange(
            "/" + bucket + "/" + key + "?partNumber=" + n + "&uploadId=" + uploadId,
            HttpMethod.PUT, new HttpEntity<>(body), Void.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getHeaders().getETag();
    }

    @Test
    void multipartRoundTripReturnsTheConcatenatedBytes() {
        restTemplate.put("/mp-bucket", null);
        byte[] p1 = new byte[5 * 1024 * 1024];
        Arrays.fill(p1, (byte) 'a');
        byte[] p2 = "tail".getBytes(StandardCharsets.UTF_8);
        String id = startUpload("mp-bucket", "dir/big.bin");
        String e1 = putPart("mp-bucket", "dir/big.bin", id, 1, p1);
        String e2 = putPart("mp-bucket", "dir/big.bin", id, 2, p2);

        String listed = restTemplate.getForObject("/mp-bucket?uploads", String.class);
        assertThat(listed).contains(id);
        String parts = restTemplate.getForObject("/mp-bucket/dir/big.bin?uploadId=" + id, String.class);
        assertThat(parts).contains("<PartNumber>1</PartNumber>").contains("<PartNumber>2</PartNumber>");

        String body = "<CompleteMultipartUpload>"
            + "<Part><PartNumber>1</PartNumber><ETag>" + e1 + "</ETag></Part>"
            + "<Part><PartNumber>2</PartNumber><ETag>" + e2 + "</ETag></Part></CompleteMultipartUpload>";
        ResponseEntity<String> done = restTemplate.postForEntity("/mp-bucket/dir/big.bin?uploadId=" + id, body, String.class);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(xmlValue(done.getBody(), "ETag")).endsWith("-2\"");

        ResponseEntity<String> again = restTemplate.postForEntity("/mp-bucket/dir/big.bin?uploadId=" + id, body, String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(xmlValue(again.getBody(), "ETag")).isEqualTo(xmlValue(done.getBody(), "ETag"));

        ResponseEntity<byte[]> got = restTemplate.getForEntity("/mp-bucket/dir/big.bin", byte[].class);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] expected = new byte[p1.length + p2.length];
        System.arraycopy(p1, 0, expected, 0, p1.length);
        System.arraycopy(p2, 0, expected, p1.length, p2.length);
        assertThat(got.getBody()).isEqualTo(expected);

        restTemplate.delete("/mp-bucket/dir/big.bin");
        restTemplate.delete("/mp-bucket");
        assertThat(restTemplate.exchange("/mp-bucket", HttpMethod.HEAD, null, Void.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void abortedUploadDoesNotBlockBucketDelete() {
        restTemplate.put("/abort-bucket", null);
        String id = startUpload("abort-bucket", "k");
        putPart("abort-bucket", "k", id, 1, "x".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> blocked = restTemplate.exchange("/abort-bucket", HttpMethod.DELETE, null, String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        restTemplate.delete("/abort-bucket/k?uploadId=" + id);
        ResponseEntity<String> gone = restTemplate.exchange("/abort-bucket/k?uploadId=" + id, HttpMethod.GET, null, String.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> deleted = restTemplate.exchange("/abort-bucket", HttpMethod.DELETE, null, String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
```

- [ ] **Step 2: Run them — they must pass first time; prove they can fail**

Run: `mvn -B -q test -Dtest=S3ApplicationIntegrationTest` — Expected: all pass.
Prove the round-trip assertion bites: temporarily change `ObjectService.getBlob`'s multipart branch to stream only the first part (`return store.get(parts....get(0).getStorageId())`), re-run — Expected: `multipartRoundTripReturnsTheConcatenatedBytes` FAILS. Revert.

- [ ] **Step 3: Full suite and commit**

Run: `mvn -B -q test` + count — Expected: `failures=0 errors=0`.
```bash
git add services/s3/src/test/java/dev/cloudlite/s3/S3ApplicationIntegrationTest.java
git commit -m "test(s3): add end-to-end multipart integration tests"
```

---

### Task 11: Chaos suite — scenario 05 and multipart-aware harness

**Files:**
- Create: `chaos/lib/s3xml.sh`, `chaos/test/test_s3xml.sh`, `chaos/scenarios/05-kill-s3-mid-multipart.sh`
- Modify: `chaos/run.sh` (source `lib/s3xml.sh`), `chaos/lib/cluster.sh` (policy actions, blob audit, teardown, multipart helpers)

**Interfaces:**
- Produces (pure, `s3xml.sh`): `xml_value <tag>` (stdin XML → first value), `xml_uploads` (stdin `ListMultipartUploadsResult` → lines `<key> <uploadId>`), `mp_etag <md5hex…>` → AWS multipart ETag, `complete_body <n:etag…>` → `CompleteMultipartUpload` XML.
- Produces (`cluster.sh`): `mp_create <key> <token>` → uploadId; `mp_put_part <key> <uploadId> <n> <file-in-pod> <token> [curl args…]` → `"<status> <etag>"`; `mp_complete <key> <uploadId> <body> <token>` → `"<status> <etag>"`; `mp_list_uploads <token>` → `<key> <uploadId>` lines; `mp_list_parts <key> <uploadId> <token>` → raw XML.

- [ ] **Step 1: Write the failing offline tests**

`chaos/test/test_s3xml.sh`:
```bash
# shellcheck shell=bash
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/s3xml.sh
source "$CHAOS_DIR/lib/s3xml.sh"

test_mp_etag_matches_aws_algorithm() {
  # md5("hello"), md5("world") -> computed independently with Python hashlib
  assert_eq "065947336a2f2a95ba8899f3675c3be6-2" \
    "$(mp_etag 5d41402abc4b2a76b9719d911017c592 7d793037a0760186574b0282f2f435e7)" "mp_etag: two parts"
}

test_xml_value_extracts_first_tag() {
  assert_eq "abc-123" \
    "$(xml_value UploadId <<<'<InitiateMultipartUploadResult><Bucket>b</Bucket><UploadId>abc-123</UploadId></InitiateMultipartUploadResult>')" \
    "xml_value: upload id"
  assert_eq "" "$(xml_value Missing <<<'<a>b</a>')" "xml_value: missing tag is empty"
}

test_xml_uploads_lists_key_and_id_pairs() {
  local out
  out=$(xml_uploads <<<'<ListMultipartUploadsResult><Bucket>b</Bucket><Upload><Key>k1</Key><UploadId>u1</UploadId><Initiated>t</Initiated></Upload><Upload><Key>dir/k2</Key><UploadId>u2</UploadId></Upload></ListMultipartUploadsResult>')
  assert_eq $'k1 u1\ndir/k2 u2' "$out" "xml_uploads: pairs in order"
  assert_eq "" "$(xml_uploads <<<'<ListMultipartUploadsResult><Bucket>b</Bucket></ListMultipartUploadsResult>')" "xml_uploads: none"
}

test_complete_body_builds_parts_in_order() {
  assert_eq '<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"aa"</ETag></Part><Part><PartNumber>2</PartNumber><ETag>"bb"</ETag></Part></CompleteMultipartUpload>' \
    "$(complete_body 1:aa 2:bb)" "complete_body: two parts"
}
```

Run: `chaos/test/run-tests.sh` — Expected: fails, `lib/s3xml.sh: No such file or directory`.

- [ ] **Step 2: Implement `chaos/lib/s3xml.sh`**

```bash
# shellcheck shell=bash
# Pure helpers for S3 multipart XML and ETags — unit-tested offline.

# xml_value <tag> — first <tag>value</tag> on stdin, or empty.
xml_value() { sed -n "s:.*<$1>\([^<]*\)</$1>.*:\1:p" | head -n1; }

# xml_uploads — "<key> <uploadId>" per <Upload> in a ListMultipartUploadsResult on stdin.
xml_uploads() {
  awk 'BEGIN { RS = "<Upload>" } NR > 1 {
    k = $0; sub(/.*<Key>/, "", k); sub(/<\/Key>.*/, "", k)
    u = $0; sub(/.*<UploadId>/, "", u); sub(/<\/UploadId>.*/, "", u)
    print k " " u
  }'
}

# mp_etag <part md5 hex...> — AWS multipart ETag: md5 of the concatenated
# binary digests, then "-<part count>". Pure bash: printf emits the bytes.
mp_etag() {
  local all="" h
  for h in "$@"; do all+=$h; done
  # shellcheck disable=SC2059 # the \xHH escapes are the point
  printf "$(sed 's/../\\x&/g' <<<"$all")" | md5sum | cut -d' ' -f1 | sed "s/\$/-$#/"
}

# complete_body <partNumber:etag...> — CompleteMultipartUpload request body.
complete_body() {
  local p out="<CompleteMultipartUpload>"
  for p in "$@"; do
    out+="<Part><PartNumber>${p%%:*}</PartNumber><ETag>\"${p#*:}\"</ETag></Part>"
  done
  echo "$out</CompleteMultipartUpload>"
}
```

In `chaos/run.sh`, after `source "$CHAOS_DIR/lib/report.sh"` add `source "$CHAOS_DIR/lib/s3xml.sh"`.

Run: `chaos/test/run-tests.sh` and `find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x` — Expected: `failed: 0`, no lint output.

- [ ] **Step 3: Harness changes in `chaos/lib/cluster.sh`**

1. In `setup_identity`, the policy `actions` list becomes:
```
["s3:CreateBucket", "s3:DeleteBucket", "s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts", "s3:ListBucketMultipartUploads"]
```
2. In `blob_audit`, the `ids=` query becomes:
```bash
  ids=$(kc exec statefulset/postgres -- psql -U cloudlite -d cloudlite -tAc \
    'select storage_id from objects where storage_id is not null union select storage_id from upload_parts' </dev/null | sort)
```
3. Add multipart helpers after `s3_get_sha256`:
```bash
# mp_create <key> <token> — prints the new uploadId.
mp_create() {
  echo "$1" >> "$RUN_DIR/keys"
  client_curl -X POST "$S3_URL/$CHAOS_BUCKET/$1?uploads" -H "Authorization: Bearer $2" | xml_value UploadId
}

# mp_put_part <key> <uploadId> <n> <file-in-pod> <token> [extra curl args...] — prints "<status> <etag>".
mp_put_part() {
  local key=$1 id=$2 n=$3 file=$4 token=$5
  shift 5
  # shellcheck disable=SC2016 # expanded by sh inside the pod
  client_sh '
    url=$1; f=$2; tok=$3; shift 3
    h=$(mktemp)
    c=$(curl -s -o /dev/null -D "$h" -w "%{http_code}" -T "$f" -H "Authorization: Bearer $tok" "$@" "$url")
    e=$(grep -i "^etag:" "$h" | tr -d "\r\"" | cut -d" " -f2)
    rm -f "$h"
    echo "$c ${e:--}"' "$S3_URL/$CHAOS_BUCKET/$key?partNumber=$n&uploadId=$id" "$file" "$token" "$@"
}

# mp_complete <key> <uploadId> <xml body> <token> — prints "<status> <etag>".
mp_complete() {
  # shellcheck disable=SC2016 # expanded by sh inside the pod
  client_sh '
    b=$(mktemp)
    c=$(curl -s -o "$b" -w "%{http_code}" --max-time 30 -X POST --data-binary "$3" -H "Authorization: Bearer $4" "$1")
    e=$(sed -n "s:.*<ETag>\"*\([^<\"]*\)\"*</ETag>.*:\1:p" "$b")
    rm -f "$b"
    echo "$c ${e:--}"' "$S3_URL/$CHAOS_BUCKET/$1?uploadId=$2" "$1" "$3" "$4"
}

# mp_list_uploads <token> — "<key> <uploadId>" lines for in-progress uploads.
mp_list_uploads() {
  client_curl "$S3_URL/$CHAOS_BUCKET?uploads" -H "Authorization: Bearer $1" | xml_uploads
}

# mp_list_parts <key> <uploadId> <token> — raw ListPartsResult XML.
mp_list_parts() {
  client_curl "$S3_URL/$CHAOS_BUCKET/$1?uploadId=$2" -H "Authorization: Bearer $3"
}
```
4. In `teardown`, inside the `if [[ -n $tok && $tok != null ]]` block and **before** the object-delete loop:
```bash
      while read -r key uid; do
        [[ -n $uid ]] && client_curl -X DELETE -o /dev/null -H "Authorization: Bearer $tok" \
          "$S3_URL/$CHAOS_BUCKET/$key?uploadId=$uid" 2>/dev/null
      done < <(mp_list_uploads "$tok" 2>/dev/null)
```

- [ ] **Step 4: Write scenario 05**

`chaos/scenarios/05-kill-s3-mid-multipart.sh`:
```bash
# shellcheck shell=bash
# 05 — Kill S3 mid-multipart. Uploaded parts must survive and the upload must be
# resumable; a complete interrupted by a kill must be retryable with the same result.

MIB=$((1024 * 1024))

scenario_run() {
  local id=05 token uid e1 e2 e3 e4 res sum got listed parts_xml body want
  record_metric "$id" title "Kill S3 mid-multipart"
  token=$(iam_token)

  # shellcheck disable=SC2016 # expanded by sh inside the pod
  sum=$(client_sh '
    for n in 1 2 3; do head -c "$1" /dev/urandom > /tmp/mp$n; done
    head -c "$2" /dev/urandom > /tmp/mp4
    cat /tmp/mp1 /tmp/mp2 /tmp/mp3 /tmp/mp4 | sha256sum | cut -d" " -f1' $((5 * MIB)) $((1 * MIB)))

  record_metric "$id" window_start_ms "$(now_ms)"
  uid=$(mp_create s05-a "$token")
  e1=$(mp_put_part s05-a "$uid" 1 /tmp/mp1 "$token"); e1=${e1#* }
  e2=$(mp_put_part s05-a "$uid" 2 /tmp/mp2 "$token"); e2=${e2#* }

  mp_put_part s05-a "$uid" 3 /tmp/mp3 "$token" --limit-rate 1M > "$RUN_DIR/05-part3.out" 2>/dev/null &
  local put_pid=$!
  sleep 1
  kill_pod s3
  wait "$put_pid" || true
  record_result "$id" interrupted-part INFO "part 3 during the kill: $(cat "$RUN_DIR/05-part3.out")"

  if ! wait_healthy 300; then
    record_result "$id" stack FAIL "stack not healthy within 300s of the kill"
    return 0
  fi

  listed=$(mp_list_uploads "$token")
  if grep -q " $uid\$" <<<"$listed"; then
    record_result "$id" rediscover-upload PASS "ListMultipartUploads still lists the upload after the restart"
  else
    record_result "$id" rediscover-upload FAIL "upload $uid missing from ListMultipartUploads: ${listed:-empty}"
  fi

  parts_xml=$(mp_list_parts s05-a "$uid" "$token")
  if [[ $parts_xml == *"<PartNumber>1</PartNumber><ETag>\"$e1\"</ETag>"* && $parts_xml == *"<PartNumber>2</PartNumber><ETag>\"$e2\"</ETag>"* ]]; then
    record_result "$id" parts-survived PASS "parts 1-2 listed with their original ETags"
  else
    record_result "$id" parts-survived FAIL "parts 1-2 not intact after restart"
  fi

  e3=$(mp_put_part s05-a "$uid" 3 /tmp/mp3 "$token"); e3=${e3#* }
  e4=$(mp_put_part s05-a "$uid" 4 /tmp/mp4 "$token"); e4=${e4#* }
  want=$(mp_etag "$e1" "$e2" "$e3" "$e4")
  res=$(mp_complete s05-a "$uid" "$(complete_body "1:$e1" "2:$e2" "3:$e3" "4:$e4")" "$token")
  got=$(s3_get_sha256 s05-a "$token")
  if [[ $res == "200 $want" && $got == "200 $sum" ]]; then
    record_result "$id" resumed-complete PASS "resumed upload completed with ETag $want; object sha256 matches"
  else
    record_result "$id" resumed-complete FAIL "complete returned '$res' (want 200 $want); GET returned '${got%% *}'"
  fi

  # Second upload: kill S3 right after firing complete. Either outcome is valid;
  # a retry must then succeed with the same ETag.
  uid=$(mp_create s05-b "$token")
  e1=$(mp_put_part s05-b "$uid" 1 /tmp/mp1 "$token"); e1=${e1#* }
  e2=$(mp_put_part s05-b "$uid" 2 /tmp/mp4 "$token"); e2=${e2#* }
  # shellcheck disable=SC2016 # expanded by sh inside the pod
  sum=$(client_sh 'cat /tmp/mp1 /tmp/mp4 | sha256sum | cut -d" " -f1')
  want=$(mp_etag "$e1" "$e2")
  body=$(complete_body "1:$e1" "2:$e2")
  mp_complete s05-b "$uid" "$body" "$token" > "$RUN_DIR/05-complete1.out" 2>/dev/null &
  local complete_pid=$!
  kill_pod s3
  wait "$complete_pid" || true
  record_result "$id" first-complete INFO "complete in flight during the kill returned: $(cat "$RUN_DIR/05-complete1.out") (either a result or a dropped connection is valid)"

  if ! wait_healthy 300; then
    record_result "$id" stack FAIL "stack not healthy within 300s of the second kill"
    return 0
  fi
  record_metric "$id" window_end_ms "$(now_ms)"

  res=$(mp_complete s05-b "$uid" "$body" "$token")
  got=$(s3_get_sha256 s05-b "$token")
  if [[ $res == "200 $want" && $got == "200 $sum" ]]; then
    record_result "$id" retried-complete PASS "retried complete returned 200 with ETag $want; object sha256 matches"
  else
    record_result "$id" retried-complete FAIL "retry returned '$res' (want 200 $want); GET returned '${got%% *}'"
  fi

  client_sh 'rm -f /tmp/mp1 /tmp/mp2 /tmp/mp3 /tmp/mp4'
}
```

- [ ] **Step 5: Offline checks and commit**

Run: `chaos/test/run-tests.sh && find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x` — Expected: `failed: 0`, no lint output.
```bash
git add chaos
git commit -m "feat(chaos): add scenario 05, kill S3 mid-multipart and on complete"
```

---

### Task 12: Live validation on k3d, first report, docs

**Prerequisite — stand up the stack** (the cluster was deleted after the last sub-project). Run from the repo root, in this order:
```bash
k3d cluster create cloudlite-test --image rancher/k3s:v1.28.15-k3s1 --wait
kubectl config use-context k3d-cloudlite-test
kubectl create namespace sealed-secrets
kubectl apply -f deploy/argocd/install/sealed-secrets-install.yaml
kubectl rollout status deployment/sealed-secrets-controller -n sealed-secrets --timeout=180s
KS=$(mktemp -d); curl -fsSL "https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.39.1/kubeseal-0.39.1-linux-amd64.tar.gz" | tar -xz -C "$KS" kubeseal
kubectl create namespace cloudlite
PG=$(openssl rand -hex 16); JWT=$(openssl rand -base64 32); GF=$(openssl rand -hex 12)
seal() { "$KS/kubeseal" --format=yaml --controller-namespace=sealed-secrets > "$1"; }
kubectl create secret generic postgres-credentials -n cloudlite --from-literal=POSTGRES_PASSWORD="$PG" --from-literal=SPRING_DATASOURCE_PASSWORD="$PG" --dry-run=client -o yaml | seal deploy/helm/cloudlite/templates/postgres/sealedsecret.yaml
kubectl create secret generic iam-jwt-secret -n cloudlite --from-literal=IAM_JWT_SECRET="$JWT" --dry-run=client -o yaml | seal deploy/helm/cloudlite/charts/iam/templates/sealedsecret.yaml
kubectl create secret generic grafana-admin -n cloudlite --from-literal=admin-user=admin --from-literal=admin-password="$GF" --dry-run=client -o yaml | seal deploy/helm/cloudlite/templates/grafana/sealedsecret.yaml
docker build -q -t s3:0.1.0 services/s3 && docker build -q -t iam:0.1.0 services/iam
k3d image import s3:0.1.0 iam:0.1.0 -c cloudlite-test
helm dependency build deploy/helm/cloudlite
helm install cloudlite deploy/helm/cloudlite -n cloudlite -f deploy/helm/cloudlite/values-dev.yaml
kubectl wait -n cloudlite --for=condition=Ready pod -l 'app in (s3,iam,postgres)' --timeout=300s
```
**Never commit the re-sealed `sealedsecret.yaml` files** — revert them in Step 4.

**Files:**
- Create: `chaos/reports/<first-run>.md`
- Modify: `docs/services/s3.md`, `docs/future-work.md`, `docs/platform/chaos.md`

- [ ] **Step 1: Live smoke of the reconciler wiring**

```bash
kubectl logs -n cloudlite deploy/s3 | grep -m1 "reconciler run"
kubectl exec -n cloudlite deploy/s3 -- sh -c 'wget -qO- localhost:8080/actuator/prometheus 2>/dev/null || curl -s localhost:8080/actuator/prometheus' | grep -E '^s3_(reconciler|multipart)'
```
Expected: one INFO `s3: reconciler run: expired=0 pruned=0 blobsDeleted=0 failedSteps=[]` line; `s3_reconciler_uploads_expired_total 0.0`, `s3_reconciler_blobs_deleted_total 0.0`, `s3_reconciler_runs_total{…outcome="ok"…} 1.0`, `s3_multipart_uploads_in_progress 0.0`.

- [ ] **Step 2: Run scenario 05, then the full suite**

Run: `chaos/run.sh 05; echo "exit=$?"` — Expected: `rediscover-upload`, `parts-survived`, `resumed-complete`, `retried-complete` PASS; `exit=0`. Any FAIL: read `chaos/reports/raw/<id>/` and S3 logs; a harness bug gets fixed, a real service defect is a finding.
Delete that report, then run: `chaos/run.sh; echo "exit=$?"` — Expected: all five scenarios in the summary; `blob-audit` in 04 PASS (now counting part blobs as live). Commit this report:
```bash
git add chaos/reports/*.md
git commit -m "docs(chaos): add chaos run report including multipart scenario"
```

- [ ] **Step 3: Docs**

`docs/services/s3.md`:
- Status line: "Phase 1 (foundation) and Phase 4 (multipart upload with crash recovery) built … Phases 2–3 (byte-range GET + tags, versioning) deferred to `../future-work.md` (MVP decision, 2026-10-06)." Link this plan and the spec.
- New `## Multipart upload` section: the six operations table (from the spec §5/§6), part rules, multipart ETag, idempotent complete, the manifest model (no copy on complete), the reconciler (three steps, defaults, metrics, single-replica assumption).
- Remove "Multipart upload … with crash recovery" from the unbuilt list; keep versioning/ranges/tags in Scope with "(deferred)".

`docs/future-work.md` — under "## S3 clone — out of scope" add:
```markdown
- Versioning, byte-range GET, and custom object tags (S3 Phases 2–3) — deferred
  on 2026-10-06 when the MVP was defined as multipart + real-node deploy; neither
  interview pitch needs them. Revisit if a target role emphasises S3 API breadth.
- Pagination on ListParts / ListMultipartUploads (`max-parts`, `max-uploads`,
  markers) — unneeded at this scale (≤10,000 parts fits one response).
- Streaming request bodies to disk instead of buffering parts in memory — revisit
  if parts larger than 100 MiB are needed.
- Reconciler coordination for multiple S3 replicas (Postgres advisory lock) —
  S3 is single-replica by design.
```

`docs/platform/chaos.md`: add scenario 05 to the scope table; add its first-run results to "First-run findings"; update the report link to the new report.

- [ ] **Step 4: Clean up and final verification**

```bash
git checkout -- deploy/helm/cloudlite/templates/postgres/sealedsecret.yaml \
  deploy/helm/cloudlite/charts/iam/templates/sealedsecret.yaml \
  deploy/helm/cloudlite/templates/grafana/sealedsecret.yaml
(cd services/s3 && mvn -B -q test)   # + count command → failures=0 errors=0
chaos/test/run-tests.sh && find chaos -name '*.sh' -print0 | xargs -0 shellcheck -x
git status --short   # only the doc files (and untracked scratch) should show
git add docs/services/s3.md docs/future-work.md docs/platform/chaos.md
git commit -m "docs: document S3 multipart upload and defer versioning/ranges/tags"
```
Leave the k3d cluster running only if the user wants it; otherwise `k3d cluster delete cloudlite-test`.
