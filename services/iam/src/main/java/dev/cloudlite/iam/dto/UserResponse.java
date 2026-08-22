package dev.cloudlite.iam.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(UUID id, String username, OffsetDateTime createdAt) {
}
