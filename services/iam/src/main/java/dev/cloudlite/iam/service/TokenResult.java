package dev.cloudlite.iam.service;

public record TokenResult(String token, long expiresInSeconds) {
}
