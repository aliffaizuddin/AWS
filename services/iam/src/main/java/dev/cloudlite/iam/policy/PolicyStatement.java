package dev.cloudlite.iam.policy;

import java.util.List;

public record PolicyStatement(Effect effect, List<String> actions, List<String> resources) {
}
