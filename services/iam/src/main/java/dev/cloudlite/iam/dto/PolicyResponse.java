package dev.cloudlite.iam.dto;

import dev.cloudlite.iam.policy.PolicyDocument;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PolicyResponse(UUID id, String name, PolicyDocument document, OffsetDateTime createdAt) {
}
