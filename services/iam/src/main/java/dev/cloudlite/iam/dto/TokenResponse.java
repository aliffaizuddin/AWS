package dev.cloudlite.iam.dto;

public record TokenResponse(String token, long expiresIn) {
}
