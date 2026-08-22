package dev.cloudlite.iam.repository;

import dev.cloudlite.iam.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByUsername(String username);

    Optional<User> findByApiKeyHash(String apiKeyHash);
}
