package dev.cloudlite.s3.iamclient;

public record AuthorizeRequestBody(String action, String resource) {
}
