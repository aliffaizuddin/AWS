package dev.cloudlite.s3.storage;

public class BlobNotFoundException extends RuntimeException {
    public BlobNotFoundException(String message) {
        super(message);
    }
}
