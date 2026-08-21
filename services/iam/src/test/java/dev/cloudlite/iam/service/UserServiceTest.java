package dev.cloudlite.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cloudlite.iam.domain.User;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.repository.PolicyRepository;
import dev.cloudlite.iam.repository.RoleRepository;
import dev.cloudlite.iam.repository.UserPolicyRepository;
import dev.cloudlite.iam.repository.UserRepository;
import dev.cloudlite.iam.repository.UserRoleRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserServiceTest {

    private UserRepository users;
    private RoleRepository roles;
    private PolicyRepository policies;
    private UserRoleRepository userRoles;
    private UserPolicyRepository userPolicies;
    private UserService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        roles = mock(RoleRepository.class);
        policies = mock(PolicyRepository.class);
        userRoles = mock(UserRoleRepository.class);
        userPolicies = mock(UserPolicyRepository.class);
        service = new UserService(users, roles, policies, userRoles, userPolicies);
    }

    @Test
    void createRejectsABlankUsernameAsInvalidArgument() {
        assertThatThrownBy(() -> service.create("  "))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void createRejectsADuplicateUsername() {
        when(users.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.create("alice"))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.USER_ALREADY_EXISTS);
    }

    @Test
    void createSavesANewUserWithAHashedApiKey() {
        when(users.existsByUsername("alice")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NewUser result = service.create("alice");

        assertThat(result.user().getUsername()).isEqualTo("alice");
        assertThat(result.apiKey()).isNotBlank();
        assertThat(result.user().getApiKeyHash()).isNotEqualTo(result.apiKey());
    }

    @Test
    void getThrowsWhenTheUserIsMissing() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.USER_NOT_FOUND);
    }

    @Test
    void attachRoleThrowsWhenTheUserIsMissing() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(users.existsById(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.attachRole(userId, roleId))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.USER_NOT_FOUND);
    }

    @Test
    void attachRoleThrowsWhenTheRoleIsMissing() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(users.existsById(userId)).thenReturn(true);
        when(roles.existsById(roleId)).thenReturn(false);

        assertThatThrownBy(() -> service.attachRole(userId, roleId))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.ROLE_NOT_FOUND);
    }

    @Test
    void attachRoleSavesTheMembershipWhenBothExist() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(users.existsById(userId)).thenReturn(true);
        when(roles.existsById(roleId)).thenReturn(true);

        service.attachRole(userId, roleId);

        verify(userRoles).save(any());
    }

    @Test
    void attachPolicyThrowsWhenThePolicyIsMissing() {
        UUID userId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        when(users.existsById(userId)).thenReturn(true);
        when(policies.existsById(policyId)).thenReturn(false);

        assertThatThrownBy(() -> service.attachPolicy(userId, policyId))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.POLICY_NOT_FOUND);
    }
}
