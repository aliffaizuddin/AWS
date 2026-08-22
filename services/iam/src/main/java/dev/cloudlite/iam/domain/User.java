package dev.cloudlite.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "api_key_hash", nullable = false)
    private String apiKeyHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected User() {
        // for JPA
    }

    public User(String username, String apiKeyHash) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.apiKeyHash = apiKeyHash;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
