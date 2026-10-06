package dev.cloudlite.s3.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "multipart_uploads")
public class MultipartUpload {

    @Id
    @Column(name = "upload_id")
    private UUID uploadId;

    @Column(name = "bucket_name", nullable = false)
    private String bucketName;

    // "key" is a JPQL reserved word, so the attribute is objectKey.
    @Column(name = "key", nullable = false)
    private String objectKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UploadStatus status;

    @Column
    private String etag;

    @Column(name = "initiated_at", nullable = false)
    private OffsetDateTime initiatedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected MultipartUpload() {
        // for JPA
    }

    public MultipartUpload(UUID uploadId, String bucketName, String objectKey, String contentType) {
        this.uploadId = uploadId;
        this.bucketName = bucketName;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.status = UploadStatus.IN_PROGRESS;
        this.initiatedAt = OffsetDateTime.now();
        this.updatedAt = this.initiatedAt;
    }

    public boolean belongsTo(String bucket, String key) {
        return bucketName.equals(bucket) && objectKey.equals(key);
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public void complete(String etag) {
        this.status = UploadStatus.COMPLETED;
        this.etag = etag;
        touch();
    }

    public void abort() {
        this.status = UploadStatus.ABORTED;
        touch();
    }

    public UUID getUploadId() { return uploadId; }
    public String getBucketName() { return bucketName; }
    public String getObjectKey() { return objectKey; }
    public String getContentType() { return contentType; }
    public UploadStatus getStatus() { return status; }
    public String getEtag() { return etag; }
    public OffsetDateTime getInitiatedAt() { return initiatedAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
