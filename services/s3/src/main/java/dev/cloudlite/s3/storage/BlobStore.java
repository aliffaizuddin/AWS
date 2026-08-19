package dev.cloudlite.s3.storage;

import java.io.InputStream;
import java.util.UUID;

public interface BlobStore {
    void put(UUID id, InputStream in);
    InputStream get(UUID id);
    void delete(UUID id);
}
