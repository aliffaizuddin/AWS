package dev.cloudlite.iam.policy;

import java.util.List;

public record PolicyDocument(List<PolicyStatement> statements) {
}
