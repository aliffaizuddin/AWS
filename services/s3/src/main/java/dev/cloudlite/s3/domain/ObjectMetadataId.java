package dev.cloudlite.s3.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ObjectMetadataId implements Serializable {

    private String bucketName;
    private String key;

    protected ObjectMetadataId() {
        // for JPA
    }

    public ObjectMetadataId(String bucketName, String key) {
        this.bucketName = bucketName;
        this.key = key;
    }

    public String getBucketName() {
        return bucketName;
    }

    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ObjectMetadataId that)) {
            return false;
        }
        return Objects.equals(bucketName, that.bucketName) && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucketName, key);
    }
}
