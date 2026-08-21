CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id),
    role_id UUID NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE user_policies (
    user_id   UUID NOT NULL REFERENCES users(id),
    policy_id UUID NOT NULL REFERENCES policies(id),
    PRIMARY KEY (user_id, policy_id)
);

CREATE TABLE role_policies (
    role_id   UUID NOT NULL REFERENCES roles(id),
    policy_id UUID NOT NULL REFERENCES policies(id),
    PRIMARY KEY (role_id, policy_id)
);
