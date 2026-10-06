package dev.cloudlite.s3.service;

public record CompletedPart(int partNumber, String etag) {
}
