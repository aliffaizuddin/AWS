package dev.cloudlite.s3.service;

public record CompletionResult(String bucket, String key, String etag) {
}
