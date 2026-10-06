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
            part(u.getUploadId(), 1, 1, "5d41402abc4b2a76b9719d911017c592")));
        when(objects.findById(new ObjectMetadataId("photos", "big.bin"))).thenReturn(Optional.of(
            ObjectMetadata.multipart("photos", "big.bin", "text/plain", 1, "x-1", previousUpload)));

        service.complete("photos", "big.bin", u.getUploadId(), List.of(new CompletedPart(1, "5d41402abc4b2a76b9719d911017c592")));

        verify(cleaner).discardQuietly(previousUpload);
    }

    @Test
    void completeOverExistingSingleBlobObjectDeletesThatBlob() {
        MultipartUpload u = inProgress("photos", "big.bin");
        UUID oldBlob = UUID.randomUUID();
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(u.getUploadId())).thenReturn(List.of(
            part(u.getUploadId(), 1, 1, "5d41402abc4b2a76b9719d911017c592")));
        when(objects.findById(new ObjectMetadataId("photos", "big.bin"))).thenReturn(Optional.of(
            new ObjectMetadata("photos", "big.bin", "text/plain", 1, "e", oldBlob)));

        service.complete("photos", "big.bin", u.getUploadId(), List.of(new CompletedPart(1, "5d41402abc4b2a76b9719d911017c592")));

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
