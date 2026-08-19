package dev.cloudlite.s3.service;

import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.repository.BucketRepository;
import dev.cloudlite.s3.repository.ObjectRepository;
import dev.cloudlite.s3.storage.BlobStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ObjectService {

    private static final Logger log = LoggerFactory.getLogger(ObjectService.class);
    private static final long MAX_OBJECT_SIZE = 100L * 1024 * 1024; // 100 MiB

    private final BucketRepository buckets;
    private final ObjectRepository objects;
    private final BlobStore store;

    public ObjectService(BucketRepository buckets, ObjectRepository objects, BlobStore store) {
        this.buckets = buckets;
        this.objects = objects;
        this.store = store;
    }

    public long maxObjectSize() {
        return MAX_OBJECT_SIZE;
    }

    public String put(String bucket, String key, byte[] body, String contentType) {
        if (!buckets.existsById(bucket)) {
            throw new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, bucket);
        }

        Optional<ObjectMetadata> existing = objects.findById(new ObjectMetadataId(bucket, key));

        String etag = md5Hex(body);
        UUID storageId = UUID.randomUUID();
        store.put(storageId, new ByteArrayInputStream(body));

        String resolvedContentType = (contentType == null || contentType.isBlank())
            ? "application/octet-stream"
            : contentType;

        try {
            objects.save(new ObjectMetadata(bucket, key, resolvedContentType, body.length, etag, storageId));
        } catch (RuntimeException e) {
            log.error("s3: put object {}/{}: blob {} written but metadata upsert failed, blob is orphaned",
                bucket, key, storageId, e);
            throw e;
        }

        existing.ifPresent(old -> {
            try {
                store.delete(old.getStorageId());
            } catch (RuntimeException e) {
                log.warn("s3: put object {}/{}: failed to delete superseded blob {}", bucket, key, old.getStorageId(), e);
            }
        });

        return etag;
    }

    public ObjectMetadata get(String bucket, String key) {
        return objects.findById(new ObjectMetadataId(bucket, key))
            .orElseThrow(() -> new S3ApiException(S3ErrorCode.NO_SUCH_KEY, key));
    }

    public InputStream getBlob(ObjectMetadata metadata) {
        return store.get(metadata.getStorageId());
    }

    public Optional<ObjectMetadata> find(String bucket, String key) {
        return objects.findById(new ObjectMetadataId(bucket, key));
    }

    public void delete(String bucket, String key) {
        ObjectMetadataId id = new ObjectMetadataId(bucket, key);
        Optional<ObjectMetadata> existing = objects.findById(id);
        if (existing.isEmpty()) {
            return;
        }
        objects.deleteById(id);
        try {
            store.delete(existing.get().getStorageId());
        } catch (RuntimeException e) {
            log.warn("s3: delete object {}/{}: blob {} delete failed after metadata delete, blob is orphaned",
                bucket, key, existing.get().getStorageId(), e);
        }
    }

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
