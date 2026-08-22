package dev.cloudlite.iam.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UserPolicyId implements Serializable {

    private UUID userId;
    private UUID policyId;

    protected UserPolicyId() {
        // for JPA
    }

    public UserPolicyId(UUID userId, UUID policyId) {
        this.userId = userId;
        this.policyId = policyId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserPolicyId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(policyId, that.policyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, policyId);
    }
}
