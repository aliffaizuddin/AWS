package dev.cloudlite.s3.service;

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
import dev.cloudlite.s3.util.Md5;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class MultipartService {

    public static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    public static final int MAX_PART_NUMBER = 10_000;

    private final BucketRepository buckets;
    private final MultipartUploadRepository uploads;
    private final UploadPartRepository parts;
    private final ObjectRepository objects;
    private final BlobStore store;
    private final UploadCleaner cleaner;
    private final TransactionOperations tx;

    public MultipartService(BucketRepository buckets, MultipartUploadRepository uploads, UploadPartRepository parts,
                            ObjectRepository objects, BlobStore store, UploadCleaner cleaner,
                            TransactionOperations tx) {
        this.buckets = buckets;
        this.uploads = uploads;
        this.parts = parts;
        this.objects = objects;
        this.store = store;
        this.cleaner = cleaner;
        this.tx = tx;
    }

    public MultipartUpload create(String bucket, String key, String contentType) {
        if (!buckets.existsById(bucket)) {
            throw new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, bucket);
        }
        String resolved = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
        return uploads.save(new MultipartUpload(UUID.randomUUID(), bucket, key, resolved));
    }

    public String uploadPart(String bucket, String key, UUID uploadId, int partNumber, byte[] body) {
        if (partNumber < 1 || partNumber > MAX_PART_NUMBER) {
            throw new S3ApiException(S3ErrorCode.INVALID_ARGUMENT, "partNumber");
        }
        requireInProgress(bucket, key, uploadId); // fail fast before writing a blob

        String etag = Md5.hex(body);
        UUID storageId = UUID.randomUUID();
        store.put(storageId, new ByteArrayInputStream(body));

        UUID superseded = tx.execute(status -> {
            MultipartUpload upload = lockInProgress(bucket, key, uploadId);
            UUID previous = parts.findById(new UploadPartId(uploadId, partNumber))
                .map(UploadPart::getStorageId).orElse(null);
            parts.save(new UploadPart(uploadId, partNumber, storageId, body.length, etag));
            upload.touch();
            return previous;
        });
        if (superseded != null) {
            cleaner.deleteBlobsQuietly(List.of(superseded));
        }
        return etag;
    }

    public CompletionResult complete(String bucket, String key, UUID uploadId, List<CompletedPart> requested) {
        Completion c = tx.execute(status -> completeLocked(bucket, key, uploadId, requested));
        cleaner.deleteBlobsQuietly(c.garbageBlobs());
        c.supersededUpload().ifPresent(cleaner::discardQuietly);
        return c.result();
    }

    private Completion completeLocked(String bucket, String key, UUID uploadId, List<CompletedPart> requested) {
        MultipartUpload upload = uploads.findForUpdate(uploadId)
            .filter(u -> u.belongsTo(bucket, key))
            .orElseThrow(() -> noSuchUpload(uploadId));
        if (upload.getStatus() == UploadStatus.COMPLETED) {
            return new Completion(new CompletionResult(bucket, key, upload.getEtag()), List.of(), Optional.empty());
        }
        if (upload.getStatus() != UploadStatus.IN_PROGRESS) {
            throw noSuchUpload(uploadId);
        }
        if (requested.isEmpty()) {
            throw new S3ApiException(S3ErrorCode.MALFORMED_XML, "");
        }

        Map<Integer, UploadPart> stored = parts.findByIdUploadIdOrderByIdPartNumberAsc(uploadId).stream()
            .collect(Collectors.toMap(UploadPart::getPartNumber, Function.identity()));
        List<UploadPart> chosen = new ArrayList<>();
        int previous = 0;
        for (CompletedPart r : requested) {
            if (r.partNumber() <= previous) {
                throw new S3ApiException(S3ErrorCode.INVALID_PART_ORDER, "");
            }
            previous = r.partNumber();
            UploadPart p = stored.get(r.partNumber());
            if (p == null || !p.getEtag().equals(stripQuotes(r.etag()))) {
                throw new S3ApiException(S3ErrorCode.INVALID_PART, Integer.toString(r.partNumber()));
            }
            chosen.add(p);
        }
        for (int i = 0; i < chosen.size() - 1; i++) {
            if (chosen.get(i).getSizeBytes() < MIN_PART_SIZE) {
                throw new S3ApiException(S3ErrorCode.ENTITY_TOO_SMALL, Integer.toString(chosen.get(i).getPartNumber()));
            }
        }

        List<UUID> garbage = new ArrayList<>();
        Set<Integer> keep = new HashSet<>();
        chosen.forEach(p -> keep.add(p.getPartNumber()));
        for (UploadPart p : stored.values()) {
            if (!keep.contains(p.getPartNumber())) {
                garbage.add(p.getStorageId());
                parts.delete(p);
            }
        }

        UUID supersededUpload = null;
        Optional<ObjectMetadata> existing = objects.findById(new ObjectMetadataId(bucket, key));
        if (existing.isPresent()) {
            if (existing.get().isMultipart()) {
                supersededUpload = existing.get().getUploadId();
            } else {
                garbage.add(existing.get().getStorageId());
            }
        }

        String etag = Md5.multipartEtag(chosen.stream().map(UploadPart::getEtag).toList());
        long size = chosen.stream().mapToLong(UploadPart::getSizeBytes).sum();
        objects.save(ObjectMetadata.multipart(bucket, key, upload.getContentType(), size, etag, uploadId));
        upload.complete(etag);
        return new Completion(new CompletionResult(bucket, key, etag), garbage, Optional.ofNullable(supersededUpload));
    }

    public void abort(String bucket, String key, UUID uploadId) {
        List<UUID> blobs = tx.execute(status -> abortLocked(lockInProgress(bucket, key, uploadId)));
        cleaner.deleteBlobsQuietly(blobs);
    }

    public List<UploadPart> listParts(String bucket, String key, UUID uploadId) {
        requireInProgress(bucket, key, uploadId);
        return parts.findByIdUploadIdOrderByIdPartNumberAsc(uploadId);
    }

    public MultipartUpload requireInProgress(String bucket, String key, UUID uploadId) {
        return uploads.findById(uploadId)
            .filter(u -> u.belongsTo(bucket, key) && u.getStatus() == UploadStatus.IN_PROGRESS)
            .orElseThrow(() -> noSuchUpload(uploadId));
    }

    public List<MultipartUpload> listUploads(String bucket) {
        if (!buckets.existsById(bucket)) {
            throw new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, bucket);
        }
        return uploads.findByBucketNameAndStatusOrderByObjectKeyAscInitiatedAtAsc(bucket, UploadStatus.IN_PROGRESS);
    }

    // Reconciler entry point. The cutoff is re-checked under the row lock so a
    // part that landed after the reconciler's query keeps the upload alive.
    public boolean expire(UUID uploadId, OffsetDateTime cutoff) {
        List<UUID> blobs = tx.execute(status -> uploads.findForUpdate(uploadId)
            .filter(u -> u.getStatus() == UploadStatus.IN_PROGRESS && u.getUpdatedAt().isBefore(cutoff))
            .map(this::abortLocked)
            .orElse(null));
        if (blobs == null) {
            return false;
        }
        cleaner.deleteBlobsQuietly(blobs);
        return true;
    }

    private MultipartUpload lockInProgress(String bucket, String key, UUID uploadId) {
        return uploads.findForUpdate(uploadId)
            .filter(u -> u.belongsTo(bucket, key) && u.getStatus() == UploadStatus.IN_PROGRESS)
            .orElseThrow(() -> noSuchUpload(uploadId));
    }

    private List<UUID> abortLocked(MultipartUpload upload) {
        List<UploadPart> ps = parts.findByIdUploadIdOrderByIdPartNumberAsc(upload.getUploadId());
        parts.deleteAll(ps);
        upload.abort();
        return ps.stream().map(UploadPart::getStorageId).toList();
    }

    private static S3ApiException noSuchUpload(UUID uploadId) {
        return new S3ApiException(S3ErrorCode.NO_SUCH_UPLOAD, uploadId.toString());
    }

    private static String stripQuotes(String etag) {
        return etag == null ? "" : etag.replace("\"", "").trim();
    }

    private record Completion(CompletionResult result, List<UUID> garbageBlobs, Optional<UUID> supersededUpload) {
    }
}
