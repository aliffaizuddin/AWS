package dev.cloudlite.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "policies")
public class Policy {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String document;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Policy() {
        // for JPA
    }

    public Policy(String name, String document) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.document = document;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDocument() {
        return document;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
