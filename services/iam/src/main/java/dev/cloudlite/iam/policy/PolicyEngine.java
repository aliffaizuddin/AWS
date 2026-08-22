package dev.cloudlite.iam.policy;

import java.util.List;

public final class PolicyEngine {

    private PolicyEngine() {
    }

    public static Decision evaluate(List<PolicyStatement> statements, String action, String resource) {
        boolean allowed = false;
        for (PolicyStatement statement : statements) {
            if (matchesAny(statement.actions(), action) && matchesAny(statement.resources(), resource)) {
                if (statement.effect() == Effect.DENY) {
                    return Decision.DENY;
                }
                allowed = true;
            }
        }
        return allowed ? Decision.ALLOW : Decision.DENY;
    }

    private static boolean matchesAny(List<String> patterns, String value) {
        for (String pattern : patterns) {
            if (matches(pattern, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String pattern, String value) {
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(value);
    }
}
