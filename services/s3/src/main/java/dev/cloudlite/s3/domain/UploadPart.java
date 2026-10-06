package dev.cloudlite.s3.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "upload_parts")
public class UploadPart {

    @EmbeddedId
    private UploadPartId id;

    @Column(name = "storage_id", nullable = false)
    private UUID storageId;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String etag;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UploadPart() {
        // for JPA
    }

    public UploadPart(UUID uploadId, int partNumber, UUID storageId, long sizeBytes, String etag) {
        this.id = new UploadPartId(uploadId, partNumber);
        this.storageId = storageId;
        this.sizeBytes = sizeBytes;
        this.etag = etag;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getUploadId() { return id.getUploadId(); }
    public int getPartNumber() { return id.getPartNumber(); }
    public UUID getStorageId() { return storageId; }
    public long getSizeBytes() { return sizeBytes; }
    public String getEtag() { return etag; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
