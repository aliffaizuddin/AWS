package dev.cloudlite.iam.repository;

import dev.cloudlite.iam.domain.UserPolicy;
import dev.cloudlite.iam.domain.UserPolicyId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPolicyRepository extends JpaRepository<UserPolicy, UserPolicyId> {

    List<UserPolicy> findByIdUserId(UUID userId);
}
