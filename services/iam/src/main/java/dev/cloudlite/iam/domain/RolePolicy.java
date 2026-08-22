package dev.cloudlite.iam.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "role_policies")
public class RolePolicy {

    @EmbeddedId
    @AttributeOverride(name = "roleId", column = @Column(name = "role_id"))
    @AttributeOverride(name = "policyId", column = @Column(name = "policy_id"))
    private RolePolicyId id;

    protected RolePolicy() {
        // for JPA
    }

    public RolePolicy(UUID roleId, UUID policyId) {
        this.id = new RolePolicyId(roleId, policyId);
    }

    public RolePolicyId getId() {
        return id;
    }

    public UUID getRoleId() {
        return id.getRoleId();
    }

    public UUID getPolicyId() {
        return id.getPolicyId();
    }
}
