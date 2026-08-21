package dev.cloudlite.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.cloudlite.iam.domain.Policy;
import dev.cloudlite.iam.domain.RolePolicy;
import dev.cloudlite.iam.domain.UserPolicy;
import dev.cloudlite.iam.domain.UserRole;
import dev.cloudlite.iam.policy.Decision;
import dev.cloudlite.iam.policy.Effect;
import dev.cloudlite.iam.policy.PolicyDocument;
import dev.cloudlite.iam.policy.PolicyStatement;
import dev.cloudlite.iam.repository.PolicyRepository;
import dev.cloudlite.iam.repository.RolePolicyRepository;
import dev.cloudlite.iam.repository.UserPolicyRepository;
import dev.cloudlite.iam.repository.UserRoleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorizationServiceTest {

    private UserRoleRepository userRoles;
    private UserPolicyRepository userPolicies;
    private RolePolicyRepository rolePolicies;
    private PolicyRepository policies;
    private PolicyService policyService;
    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        userRoles = mock(UserRoleRepository.class);
        userPolicies = mock(UserPolicyRepository.class);
        rolePolicies = mock(RolePolicyRepository.class);
        policies = mock(PolicyRepository.class);
        policyService = mock(PolicyService.class);
        service = new AuthorizationService(userRoles, userPolicies, rolePolicies, policies, policyService);
    }

    @Test
    void authorizeAllowsWhenADirectlyAttachedPolicyGrantsTheAction() {
        UUID userId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        Policy policy = new Policy("read-only", "{}");
        when(userPolicies.findByIdUserId(userId)).thenReturn(List.of(new UserPolicy(userId, policyId)));
        when(userRoles.findByIdUserId(userId)).thenReturn(List.of());
        when(policies.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyService.parseDocument(policy)).thenReturn(new PolicyDocument(List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::b/*")))));

        Decision decision = service.authorize(userId, "s3:GetObject", "arn:cloudlite:s3:::b/key.txt");

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void authorizeAllowsWhenARolePolicyGrantsTheAction() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        Policy policy = new Policy("developers-policy", "{}");
        when(userPolicies.findByIdUserId(userId)).thenReturn(List.of());
        when(userRoles.findByIdUserId(userId)).thenReturn(List.of(new UserRole(userId, roleId)));
        when(rolePolicies.findByIdRoleId(roleId)).thenReturn(List.of(new RolePolicy(roleId, policyId)));
        when(policies.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyService.parseDocument(policy)).thenReturn(new PolicyDocument(List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:*"), List.of("arn:cloudlite:s3:::b/*")))));

        Decision decision = service.authorize(userId, "s3:PutObject", "arn:cloudlite:s3:::b/key.txt");

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void authorizeCombinesADirectlyAttachedPolicyAndARolePolicyIntoOneEffectiveList() {
        UUID userId = UUID.randomUUID();
        UUID directPolicyId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID rolePolicyId = UUID.randomUUID();
        Policy directPolicy = new Policy("direct-policy", "{}");
        Policy rolePolicy = new Policy("role-policy", "{}");
        when(userPolicies.findByIdUserId(userId)).thenReturn(List.of(new UserPolicy(userId, directPolicyId)));
        when(userRoles.findByIdUserId(userId)).thenReturn(List.of(new UserRole(userId, roleId)));
        when(rolePolicies.findByIdRoleId(roleId)).thenReturn(List.of(new RolePolicy(roleId, rolePolicyId)));
        when(policies.findById(directPolicyId)).thenReturn(Optional.of(directPolicy));
        when(policies.findById(rolePolicyId)).thenReturn(Optional.of(rolePolicy));
        when(policyService.parseDocument(directPolicy)).thenReturn(new PolicyDocument(List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::direct-bucket/*")))));
        when(policyService.parseDocument(rolePolicy)).thenReturn(new PolicyDocument(List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:PutObject"), List.of("arn:cloudlite:s3:::role-bucket/*")))));

        Decision directDecision =
            service.authorize(userId, "s3:GetObject", "arn:cloudlite:s3:::direct-bucket/key.txt");
        Decision roleDecision = service.authorize(userId, "s3:PutObject", "arn:cloudlite:s3:::role-bucket/key.txt");

        assertThat(directDecision).isEqualTo(Decision.ALLOW);
        assertThat(roleDecision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void authorizeDeniesWhenTheUserHasNoAttachedPolicies() {
        UUID userId = UUID.randomUUID();
        when(userPolicies.findByIdUserId(userId)).thenReturn(List.of());
        when(userRoles.findByIdUserId(userId)).thenReturn(List.of());

        Decision decision = service.authorize(userId, "s3:GetObject", "arn:cloudlite:s3:::b/key.txt");

        assertThat(decision).isEqualTo(Decision.DENY);
    }
}
