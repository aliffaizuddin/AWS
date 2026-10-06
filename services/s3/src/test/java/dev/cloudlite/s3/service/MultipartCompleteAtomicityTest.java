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
        parts.save(new UploadPart(upload.getUploadId(), 1, UUID.randomUUID(), 1, "5d41402abc4b2a76b9719d911017c592"));
        parts.save(new UploadPart(upload.getUploadId(), 2, UUID.randomUUID(), 1, "b")); // unlisted: deleted inside the tx
        doThrow(new IllegalStateException("simulated crash")).when(objects).save(any());

        assertThatThrownBy(() -> service.complete("photos", "big.bin", upload.getUploadId(),
                List.of(new CompletedPart(1, "5d41402abc4b2a76b9719d911017c592"))))
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
        parts.save(new UploadPart(upload.getUploadId(), 1, UUID.randomUUID(), 1, "5d41402abc4b2a76b9719d911017c592"));

        service.complete("photos", "big.bin", upload.getUploadId(), List.of(new CompletedPart(1, "5d41402abc4b2a76b9719d911017c592")));

        assertThat(objects.findById(new ObjectMetadataId("photos", "big.bin")).orElseThrow().getUploadId())
            .isEqualTo(upload.getUploadId());
        assertThat(uploads.findById(upload.getUploadId()).orElseThrow().getStatus()).isEqualTo(UploadStatus.COMPLETED);
    }
}
