package dev.cloudlite.s3.storage;

import java.time.Instant;
import java.util.UUID;

// A file in the blob directory, as the reconciler sees it.
public record BlobEntry(String fileName, UUID blobId, boolean temp, Instant lastModified) {
}
