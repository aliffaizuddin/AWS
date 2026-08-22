package dev.cloudlite.iam.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cloudlite.iam.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UserRepository userRepository;

    @Test
    void savedUserCanBeFoundById() {
        User saved = userRepository.save(new User("alice", "hash123"));

        assertThat(userRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void existsByUsernameIsTrueOnceCreated() {
        userRepository.save(new User("bob", "hash456"));

        assertThat(userRepository.existsByUsername("bob")).isTrue();
    }

    @Test
    void existsByUsernameIsFalseForAnUnknownUsername() {
        assertThat(userRepository.existsByUsername("nobody")).isFalse();
    }

    @Test
    void findByApiKeyHashReturnsTheMatchingUser() {
        userRepository.save(new User("carol", "unique-hash-789"));

        var found = userRepository.findByApiKeyHash("unique-hash-789");

        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo("carol");
    }

    @Test
    void findByApiKeyHashIsEmptyForAnUnknownHash() {
        assertThat(userRepository.findByApiKeyHash("no-such-hash")).isEmpty();
    }
}
