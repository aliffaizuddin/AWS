package dev.cloudlite.iam.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cloudlite.iam.domain.Role;
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
class RoleRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void savedRoleCanBeFoundById() {
        Role saved = roleRepository.save(new Role("developers"));

        assertThat(roleRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void existsByNameIsTrueOnceCreated() {
        roleRepository.save(new Role("admins"));

        assertThat(roleRepository.existsByName("admins")).isTrue();
    }

    @Test
    void existsByNameIsFalseForAnUnknownName() {
        assertThat(roleRepository.existsByName("nobody-role")).isFalse();
    }
}
