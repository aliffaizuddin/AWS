package dev.cloudlite.s3.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
    void discardQuietlyDeletesRowsButLeavesBlobsToGarbageCollection() {
        // A GET may still be streaming these parts (they're opened lazily), so
        // the files stay until the reconciler's grace period has passed.
        UUID uploadId = UUID.randomUUID();
        UploadPart p = new UploadPart(uploadId, 1, UUID.randomUUID(), 1, "a");
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(uploadId)).thenReturn(List.of(p));

        cleaner.discardQuietly(uploadId);

        verify(parts).deleteAll(List.of(p));
        verify(uploads).deleteById(uploadId);
        verify(store).touch(p.getStorageId());
        verify(store, never()).delete(any());
    }

    @Test
    void discardQuietlySwallowsDatabaseErrors() {
        UUID uploadId = UUID.randomUUID();
        when(parts.findByIdUploadIdOrderByIdPartNumberAsc(uploadId)).thenThrow(new RuntimeException("db down"));

        cleaner.discardQuietly(uploadId); // must not throw
    }
}
