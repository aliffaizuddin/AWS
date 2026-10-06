package dev.cloudlite.s3.service;

import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.domain.ObjectMetadataId;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.repository.BucketRepository;
import dev.cloudlite.s3.domain.UploadPart;
import dev.cloudlite.s3.repository.ObjectRepository;
import dev.cloudlite.s3.repository.UploadPartRepository;
import dev.cloudlite.s3.storage.BlobStore;
import dev.cloudlite.s3.util.Md5;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
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
    private final UploadPartRepository parts;
    private final BlobStore store;
    private final UploadCleaner cleaner;

    public ObjectService(BucketRepository buckets, ObjectRepository objects, UploadPartRepository parts,
                         BlobStore store, UploadCleaner cleaner) {
        this.buckets = buckets;
        this.objects = objects;
        this.parts = parts;
        this.store = store;
        this.cleaner = cleaner;
    }

    public long maxObjectSize() {
        return MAX_OBJECT_SIZE;
    }

    public String put(String bucket, String key, byte[] body, String contentType) {
        if (!buckets.existsById(bucket)) {
            throw new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, bucket);
        }

        Optional<ObjectMetadata> existing = objects.findById(new ObjectMetadataId(bucket, key));

        String etag = Md5.hex(body);
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

        existing.ifPresent(this::discardBacking);

        return etag;
    }

    public ObjectMetadata get(String bucket, String key) {
        return objects.findById(new ObjectMetadataId(bucket, key))
            .orElseThrow(() -> new S3ApiException(S3ErrorCode.NO_SUCH_KEY, key));
    }

    public InputStream getBlob(ObjectMetadata metadata) {
        if (!metadata.isMultipart()) {
            return store.get(metadata.getStorageId());
        }
        List<UploadPart> manifest = parts.findByIdUploadIdOrderByIdPartNumberAsc(metadata.getUploadId());
        if (manifest.isEmpty()) {
            // Deleted or overwritten between the metadata read and here.
            throw new S3ApiException(S3ErrorCode.NO_SUCH_KEY, metadata.getKey());
        }
        Iterator<UploadPart> remaining = manifest.iterator();
        // Opens each part only when the previous one is exhausted, so at most one file is open.
        return new SequenceInputStream(new Enumeration<>() {
            @Override
            public boolean hasMoreElements() {
                return remaining.hasNext();
            }

            @Override
            public InputStream nextElement() {
                return store.get(remaining.next().getStorageId());
            }
        });
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
        discardBacking(existing.get());
    }

    private void discardBacking(ObjectMetadata old) {
        if (old.isMultipart()) {
            cleaner.discardQuietly(old.getUploadId());
            return;
        }
        try {
            store.delete(old.getStorageId());
        } catch (RuntimeException e) {
            log.warn("s3: object {}/{}: failed to delete superseded blob {}, leaving it to the reconciler",
                old.getBucketName(), old.getKey(), old.getStorageId(), e);
        }
    }
}
