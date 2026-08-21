package dev.cloudlite.s3.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cloudlite.s3.domain.Bucket;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.repository.BucketRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BucketServiceTest {

    private BucketRepository buckets;
    private ObjectRepository objects;
    private BucketService service;

    @BeforeEach
    void setUp() {
        buckets = mock(BucketRepository.class);
        objects = mock(ObjectRepository.class);
        service = new BucketService(buckets, objects);
    }

    @Test
    void createRejectsTheEmptyBucketName() {
        assertThatThrownBy(() -> service.create(""))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.INVALID_BUCKET_NAME);
    }

    @Test
    void createRejectsTheReservedHealthzName() {
        assertThatThrownBy(() -> service.create("healthz"))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.INVALID_BUCKET_NAME);
    }

    @Test
    void createRejectsADuplicateBucketName() {
        when(buckets.existsById("photos")).thenReturn(true);

        assertThatThrownBy(() -> service.create("photos"))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.BUCKET_ALREADY_EXISTS);
    }

    @Test
    void createSavesANewBucket() {
        when(buckets.existsById("photos")).thenReturn(false);

        service.create("photos");

        ArgumentCaptor<Bucket> captor = ArgumentCaptor.forClass(Bucket.class);
        verify(buckets).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("photos");
    }

    @Test
    void deleteRejectsANonEmptyBucket() {
        when(objects.existsByIdBucketName("photos")).thenReturn(true);

        assertThatThrownBy(() -> service.delete("photos"))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.BUCKET_NOT_EMPTY);
    }

    @Test
    void deleteRejectsAMissingBucket() {
        when(objects.existsByIdBucketName("photos")).thenReturn(false);
        when(buckets.existsById("photos")).thenReturn(false);

        assertThatThrownBy(() -> service.delete("photos"))
            .isInstanceOf(S3ApiException.class)
            .extracting(e -> ((S3ApiException) e).getErrorCode())
            .isEqualTo(S3ErrorCode.NO_SUCH_BUCKET);
    }

    @Test
    void listReturnsBucketsFromTheRepository() {
        when(buckets.findAllByOrderByNameAsc()).thenReturn(List.of(new Bucket("alpha")));

        assertThat(service.list()).extracting(Bucket::getName).containsExactly("alpha");
    }
}
