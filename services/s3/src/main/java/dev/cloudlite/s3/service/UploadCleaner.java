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

    private void touchBlobsQuietly(Collection<UUID> storageIds) {
        for (UUID id : storageIds) {
            try {
                store.touch(id);
            } catch (RuntimeException e) {
                log.warn("s3: failed to touch blob {}; it may be collected before an in-flight read ends", id, e);
            }
        }
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
    // overwritten or deleted). Only the rows go now: a GET may still be
    // streaming these parts, opening them one by one, so the files are
    // touched and left to the reconciler's grace period.
    public void discardQuietly(UUID uploadId) {
        try {
            List<UUID> blobs = tx.execute(status -> {
                List<UploadPart> ps = parts.findByIdUploadIdOrderByIdPartNumberAsc(uploadId);
                parts.deleteAll(ps);
                uploads.deleteById(uploadId);
                return ps.stream().map(UploadPart::getStorageId).toList();
            });
            touchBlobsQuietly(blobs == null ? List.of() : blobs);
        } catch (RuntimeException e) {
            log.warn("s3: failed to discard upload {}, leaving it to the reconciler", uploadId, e);
        }
    }
}
