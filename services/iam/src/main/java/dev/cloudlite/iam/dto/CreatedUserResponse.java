package dev.cloudlite.iam.dto;

import java.util.UUID;

public record CreatedUserResponse(UUID id, String username, String apiKey) {
}
