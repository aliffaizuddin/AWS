package dev.cloudlite.iam.dto;

import dev.cloudlite.iam.policy.PolicyDocument;

public record CreatePolicyRequest(String name, PolicyDocument document) {
}
