package dev.cloudlite.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cloudlite.iam.domain.Role;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.repository.PolicyRepository;
import dev.cloudlite.iam.repository.RolePolicyRepository;
import dev.cloudlite.iam.repository.RoleRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoleServiceTest {

    private RoleRepository roles;
    private PolicyRepository policies;
    private RolePolicyRepository rolePolicies;
    private RoleService service;

    @BeforeEach
    void setUp() {
        roles = mock(RoleRepository.class);
        policies = mock(PolicyRepository.class);
        rolePolicies = mock(RolePolicyRepository.class);
        service = new RoleService(roles, policies, rolePolicies);
    }

    @Test
    void createRejectsADuplicateName() {
        when(roles.existsByName("developers")).thenReturn(true);

        assertThatThrownBy(() -> service.create("developers"))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.ROLE_ALREADY_EXISTS);
    }

    @Test
    void createSavesANewRole() {
        when(roles.existsByName("developers")).thenReturn(false);
        when(roles.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Role role = service.create("developers");

        assertThat(role.getName()).isEqualTo("developers");
    }

    @Test
    void getThrowsWhenTheRoleIsMissing() {
        UUID id = UUID.randomUUID();
        when(roles.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.ROLE_NOT_FOUND);
    }

    @Test
    void attachPolicyThrowsWhenTheRoleIsMissing() {
        UUID roleId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        when(roles.existsById(roleId)).thenReturn(false);

        assertThatThrownBy(() -> service.attachPolicy(roleId, policyId))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.ROLE_NOT_FOUND);
    }

    @Test
    void attachPolicyThrowsWhenThePolicyIsMissing() {
        UUID roleId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        when(roles.existsById(roleId)).thenReturn(true);
        when(policies.existsById(policyId)).thenReturn(false);

        assertThatThrownBy(() -> service.attachPolicy(roleId, policyId))
            .isInstanceOf(IamApiException.class)
            .extracting(e -> ((IamApiException) e).getErrorCode())
            .isEqualTo(IamErrorCode.POLICY_NOT_FOUND);
    }

    @Test
    void attachPolicySavesTheAttachmentWhenBothExist() {
        UUID roleId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        when(roles.existsById(roleId)).thenReturn(true);
        when(policies.existsById(policyId)).thenReturn(true);

        service.attachPolicy(roleId, policyId);

        verify(rolePolicies).save(any());
    }
}
