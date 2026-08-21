package dev.cloudlite.iam.service;

import dev.cloudlite.iam.domain.User;
import dev.cloudlite.iam.domain.UserPolicy;
import dev.cloudlite.iam.domain.UserRole;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.repository.PolicyRepository;
import dev.cloudlite.iam.repository.RoleRepository;
import dev.cloudlite.iam.repository.UserPolicyRepository;
import dev.cloudlite.iam.repository.UserRepository;
import dev.cloudlite.iam.repository.UserRoleRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PolicyRepository policies;
    private final UserRoleRepository userRoles;
    private final UserPolicyRepository userPolicies;

    public UserService(
            UserRepository users,
            RoleRepository roles,
            PolicyRepository policies,
            UserRoleRepository userRoles,
            UserPolicyRepository userPolicies) {
        this.users = users;
        this.roles = roles;
        this.policies = policies;
        this.userRoles = userRoles;
        this.userPolicies = userPolicies;
    }

    public NewUser create(String username) {
        if (users.existsByUsername(username)) {
            throw new IamApiException(IamErrorCode.USER_ALREADY_EXISTS);
        }
        String apiKey = ApiKeyGenerator.generate();
        User user = users.save(new User(username, ApiKeyGenerator.hash(apiKey)));
        return new NewUser(user, apiKey);
    }

    public User get(UUID id) {
        return users.findById(id).orElseThrow(() -> new IamApiException(IamErrorCode.USER_NOT_FOUND));
    }

    public List<User> list() {
        return users.findAll();
    }

    public void attachRole(UUID userId, UUID roleId) {
        if (!users.existsById(userId)) {
            throw new IamApiException(IamErrorCode.USER_NOT_FOUND);
        }
        if (!roles.existsById(roleId)) {
            throw new IamApiException(IamErrorCode.ROLE_NOT_FOUND);
        }
        userRoles.save(new UserRole(userId, roleId));
    }

    public void attachPolicy(UUID userId, UUID policyId) {
        if (!users.existsById(userId)) {
            throw new IamApiException(IamErrorCode.USER_NOT_FOUND);
        }
        if (!policies.existsById(policyId)) {
            throw new IamApiException(IamErrorCode.POLICY_NOT_FOUND);
        }
        userPolicies.save(new UserPolicy(userId, policyId));
    }
}
