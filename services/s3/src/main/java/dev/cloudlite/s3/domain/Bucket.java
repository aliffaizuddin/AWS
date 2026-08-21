package dev.cloudlite.s3.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "buckets")
public class Bucket {

    @Id
    private String name;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Bucket() {
        // for JPA
    }

    public Bucket(String name) {
        this.name = name;
        this.createdAt = OffsetDateTime.now();
    }

    public String getName() {
        return name;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
