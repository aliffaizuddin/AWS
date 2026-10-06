package dev.cloudlite.s3.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UploadPartId implements Serializable {

    @Column(name = "upload_id")
    private UUID uploadId;

    @Column(name = "part_number")
    private int partNumber;

    protected UploadPartId() {
        // for JPA
    }

    public UploadPartId(UUID uploadId, int partNumber) {
        this.uploadId = uploadId;
        this.partNumber = partNumber;
    }

    public UUID getUploadId() { return uploadId; }
    public int getPartNumber() { return partNumber; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UploadPartId other)) return false;
        return partNumber == other.partNumber && Objects.equals(uploadId, other.uploadId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uploadId, partNumber);
    }
}
