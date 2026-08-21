package dev.cloudlite.iam.service;

import dev.cloudlite.iam.domain.Role;
import dev.cloudlite.iam.domain.RolePolicy;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.repository.PolicyRepository;
import dev.cloudlite.iam.repository.RolePolicyRepository;
import dev.cloudlite.iam.repository.RoleRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RoleService {

    private final RoleRepository roles;
    private final PolicyRepository policies;
    private final RolePolicyRepository rolePolicies;

    public RoleService(RoleRepository roles, PolicyRepository policies, RolePolicyRepository rolePolicies) {
        this.roles = roles;
        this.policies = policies;
        this.rolePolicies = rolePolicies;
    }

    public Role create(String name) {
        if (roles.existsByName(name)) {
            throw new IamApiException(IamErrorCode.ROLE_ALREADY_EXISTS);
        }
        return roles.save(new Role(name));
    }

    public Role get(UUID id) {
        return roles.findById(id).orElseThrow(() -> new IamApiException(IamErrorCode.ROLE_NOT_FOUND));
    }

    public List<Role> list() {
        return roles.findAll();
    }

    public void attachPolicy(UUID roleId, UUID policyId) {
        if (!roles.existsById(roleId)) {
            throw new IamApiException(IamErrorCode.ROLE_NOT_FOUND);
        }
        if (!policies.existsById(policyId)) {
            throw new IamApiException(IamErrorCode.POLICY_NOT_FOUND);
        }
        rolePolicies.save(new RolePolicy(roleId, policyId));
    }
}
