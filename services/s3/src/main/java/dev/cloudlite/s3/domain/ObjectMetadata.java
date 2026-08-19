package dev.cloudlite.s3.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "objects")
public class ObjectMetadata {

    @EmbeddedId
    @AttributeOverride(name = "bucketName", column = @Column(name = "bucket_name"))
    @AttributeOverride(name = "key", column = @Column(name = "key"))
    private ObjectMetadataId id;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String etag;

    @Column(name = "storage_id", nullable = false)
    private UUID storageId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ObjectMetadata() {
        // for JPA
    }

    public ObjectMetadata(String bucketName, String key, String contentType, long sizeBytes, String etag, UUID storageId) {
        this.id = new ObjectMetadataId(bucketName, key);
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.etag = etag;
        this.storageId = storageId;
        this.createdAt = OffsetDateTime.now();
    }

    public ObjectMetadataId getId() {
        return id;
    }

    public String getBucketName() {
        return id.getBucketName();
    }

    public String getKey() {
        return id.getKey();
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getEtag() {
        return etag;
    }

    public UUID getStorageId() {
        return storageId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
