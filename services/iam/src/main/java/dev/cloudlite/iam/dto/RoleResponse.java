package dev.cloudlite.iam.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RoleResponse(UUID id, String name, OffsetDateTime createdAt) {
}
