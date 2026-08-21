package dev.cloudlite.s3.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.repository.BucketRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import dev.cloudlite.s3.storage.BlobStore;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class ObjectServiceTest {

    private BucketRepository buckets;
    private ObjectRepository objects;
    private BlobStore store;
    private ObjectService service;

    @BeforeEach
    void setUp() {
        buckets = mock(BucketRepository.class);
        objects = mock(ObjectRepository.class);
        store = mock(BlobStore.class);
        service = new ObjectService(buckets, objects, store);
    }

    @Test
    void putRejectsWhenTheBucketDoesNotExist() {
        when(buckets.existsById("photos")).thenReturn(false);

        assertThatThrownBy(() -> service.put("photos", "cat.png", "hi".getBytes(), "text/plain"))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.NO_SUCH_BUCKET);
    }

    @Test
    void putDefaultsContentTypeWhenAbsent() {
        when(buckets.existsById("photos")).thenReturn(true);
        when(objects.findById(any())).thenReturn(Optional.empty());

        service.put("photos", "cat.png", "hi".getBytes(), null);

        ArgumentCaptor<ObjectMetadata> captor = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(objects).save(captor.capture());
        assertThat(captor.getValue().getContentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void putDeletesTheSupersededBlobAfterAnOverwrite() {
        UUID oldStorageId = UUID.randomUUID();
        when(buckets.existsById("photos")).thenReturn(true);
        when(objects.findById(new ObjectMetadataId("photos", "cat.png")))
            .thenReturn(Optional.of(new ObjectMetadata("photos", "cat.png", "image/png", 1L, "old-etag", oldStorageId)));

        service.put("photos", "cat.png", "hi".getBytes(), "image/png");

        verify(store).delete(oldStorageId);
    }

    @Test
    void putDoesNotAttemptToDeleteAnyBlobOnANewKey() {
        when(buckets.existsById("photos")).thenReturn(true);
        when(objects.findById(any())).thenReturn(Optional.empty());

        service.put("photos", "cat.png", "hi".getBytes(), "image/png");

        verify(store, never()).delete(any());
    }

    @Test
    void getThrowsNoSuchKeyWhenTheObjectIsMissing() {
        when(objects.findById(new ObjectMetadataId("photos", "missing.png"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("photos", "missing.png"))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.NO_SUCH_KEY);
    }

    @Test
    void deleteIsANoOpWhenTheKeyNeverExisted() {
        when(objects.findById(new ObjectMetadataId("photos", "missing.png"))).thenReturn(Optional.empty());

        service.delete("photos", "missing.png");

        verify(objects, never()).deleteById(any());
        verify(store, never()).delete(any());
    }

    @Test
    void deleteRemovesMetadataBeforeTheBlob() {
        UUID storageId = UUID.randomUUID();
        ObjectMetadataId id = new ObjectMetadataId("photos", "cat.png");
        when(objects.findById(id))
            .thenReturn(Optional.of(new ObjectMetadata("photos", "cat.png", "image/png", 1L, "etag", storageId)));

        service.delete("photos", "cat.png");

        InOrder order = inOrder(objects, store);
        order.verify(objects).deleteById(id);
        order.verify(store).delete(storageId);
    }
}
