package dev.cloudlite.iam.service;

import dev.cloudlite.iam.domain.Policy;
import dev.cloudlite.iam.domain.RolePolicy;
import dev.cloudlite.iam.domain.UserPolicy;
import dev.cloudlite.iam.domain.UserRole;
import dev.cloudlite.iam.policy.Decision;
import dev.cloudlite.iam.policy.PolicyEngine;
import dev.cloudlite.iam.policy.PolicyStatement;
import dev.cloudlite.iam.repository.PolicyRepository;
import dev.cloudlite.iam.repository.RolePolicyRepository;
import dev.cloudlite.iam.repository.UserPolicyRepository;
import dev.cloudlite.iam.repository.UserRoleRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {

    private final UserRoleRepository userRoles;
    private final UserPolicyRepository userPolicies;
    private final RolePolicyRepository rolePolicies;
    private final PolicyRepository policies;
    private final PolicyService policyService;

    public AuthorizationService(
            UserRoleRepository userRoles,
            UserPolicyRepository userPolicies,
            RolePolicyRepository rolePolicies,
            PolicyRepository policies,
            PolicyService policyService) {
        this.userRoles = userRoles;
        this.userPolicies = userPolicies;
        this.rolePolicies = rolePolicies;
        this.policies = policies;
        this.policyService = policyService;
    }

    public Decision authorize(UUID userId, String action, String resource) {
        List<PolicyStatement> statements = resolveEffectiveStatements(userId);
        return PolicyEngine.evaluate(statements, action, resource);
    }

    private List<PolicyStatement> resolveEffectiveStatements(UUID userId) {
        Set<UUID> policyIds = new LinkedHashSet<>();
        for (UserPolicy userPolicy : userPolicies.findByIdUserId(userId)) {
            policyIds.add(userPolicy.getPolicyId());
        }
        for (UserRole userRole : userRoles.findByIdUserId(userId)) {
            for (RolePolicy rolePolicy : rolePolicies.findByIdRoleId(userRole.getRoleId())) {
                policyIds.add(rolePolicy.getPolicyId());
            }
        }

        List<PolicyStatement> statements = new ArrayList<>();
        for (UUID policyId : policyIds) {
            policies.findById(policyId).ifPresent(policy -> statements.addAll(toStatements(policy)));
        }
        return statements;
    }

    private List<PolicyStatement> toStatements(Policy policy) {
        return policyService.parseDocument(policy).statements();
    }
}
