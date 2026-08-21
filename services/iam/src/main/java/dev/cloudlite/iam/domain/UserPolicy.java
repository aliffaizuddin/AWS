package dev.cloudlite.iam.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "user_policies")
public class UserPolicy {

    @EmbeddedId
    @AttributeOverride(name = "userId", column = @Column(name = "user_id"))
    @AttributeOverride(name = "policyId", column = @Column(name = "policy_id"))
    private UserPolicyId id;

    protected UserPolicy() {
        // for JPA
    }

    public UserPolicy(UUID userId, UUID policyId) {
        this.id = new UserPolicyId(userId, policyId);
    }

    public UserPolicyId getId() {
        return id;
    }

    public UUID getUserId() {
        return id.getUserId();
    }

    public UUID getPolicyId() {
        return id.getPolicyId();
    }
}
