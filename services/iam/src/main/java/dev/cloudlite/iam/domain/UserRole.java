package dev.cloudlite.iam.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "user_roles")
public class UserRole {

    @EmbeddedId
    @AttributeOverride(name = "userId", column = @Column(name = "user_id"))
    @AttributeOverride(name = "roleId", column = @Column(name = "role_id"))
    private UserRoleId id;

    protected UserRole() {
        // for JPA
    }

    public UserRole(UUID userId, UUID roleId) {
        this.id = new UserRoleId(userId, roleId);
    }

    public UserRoleId getId() {
        return id;
    }

    public UUID getUserId() {
        return id.getUserId();
    }

    public UUID getRoleId() {
        return id.getRoleId();
    }
}
