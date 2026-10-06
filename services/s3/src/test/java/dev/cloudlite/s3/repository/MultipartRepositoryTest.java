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
