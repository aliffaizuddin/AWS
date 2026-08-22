package dev.cloudlite.iam.repository;

import dev.cloudlite.iam.domain.Role;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    boolean existsByName(String name);
}
