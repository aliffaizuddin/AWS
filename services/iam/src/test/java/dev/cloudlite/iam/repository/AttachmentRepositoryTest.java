package dev.cloudlite.iam.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cloudlite.iam.domain.Policy;
import dev.cloudlite.iam.domain.Role;
import dev.cloudlite.iam.domain.RolePolicy;
import dev.cloudlite.iam.domain.User;
import dev.cloudlite.iam.domain.UserPolicy;
import dev.cloudlite.iam.domain.UserRole;
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
class AttachmentRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private UserPolicyRepository userPolicyRepository;

    @Autowired
    private RolePolicyRepository rolePolicyRepository;

    @Test
    void findByIdUserIdReturnsTheUsersRoleMemberships() {
        User user = userRepository.save(new User("dave", "hash-dave"));
        Role role = roleRepository.save(new Role("developers"));
        userRoleRepository.save(new UserRole(user.getId(), role.getId()));

        assertThat(userRoleRepository.findByIdUserId(user.getId()))
            .extracting(UserRole::getRoleId)
            .containsExactly(role.getId());
    }

    @Test
    void findByIdUserIdReturnsTheUsersDirectPolicyAttachments() {
        User user = userRepository.save(new User("erin", "hash-erin"));
        Policy policy = policyRepository.save(new Policy("read-only", "{\"statements\":[]}"));
        userPolicyRepository.save(new UserPolicy(user.getId(), policy.getId()));

        assertThat(userPolicyRepository.findByIdUserId(user.getId()))
            .extracting(UserPolicy::getPolicyId)
            .containsExactly(policy.getId());
    }

    @Test
    void findByIdRoleIdReturnsARolesPolicyAttachments() {
        Role role = roleRepository.save(new Role("admins"));
        Policy policy = policyRepository.save(new Policy("full-access", "{\"statements\":[]}"));
        rolePolicyRepository.save(new RolePolicy(role.getId(), policy.getId()));

        assertThat(rolePolicyRepository.findByIdRoleId(role.getId()))
            .extracting(RolePolicy::getPolicyId)
            .containsExactly(policy.getId());
    }
}
