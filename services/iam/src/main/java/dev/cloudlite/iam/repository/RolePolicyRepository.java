package dev.cloudlite.iam.repository;

import dev.cloudlite.iam.domain.RolePolicy;
import dev.cloudlite.iam.domain.RolePolicyId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePolicyRepository extends JpaRepository<RolePolicy, RolePolicyId> {

    List<RolePolicy> findByIdRoleId(UUID roleId);
}
