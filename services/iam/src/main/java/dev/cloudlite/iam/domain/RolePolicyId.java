package dev.cloudlite.iam.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class RolePolicyId implements Serializable {

    private UUID roleId;
    private UUID policyId;

    protected RolePolicyId() {
        // for JPA
    }

    public RolePolicyId(UUID roleId, UUID policyId) {
        this.roleId = roleId;
        this.policyId = policyId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RolePolicyId that)) {
            return false;
        }
        return Objects.equals(roleId, that.roleId) && Objects.equals(policyId, that.policyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleId, policyId);
    }
}
