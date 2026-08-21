package dev.cloudlite.iam.repository;

import dev.cloudlite.iam.domain.UserRole;
import dev.cloudlite.iam.domain.UserRoleId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    List<UserRole> findByIdUserId(UUID userId);
}
