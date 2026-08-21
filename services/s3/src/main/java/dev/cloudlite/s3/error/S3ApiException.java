package dev.cloudlite.s3.error;

public class S3ApiException extends RuntimeException {

    private final S3ErrorCode errorCode;
    private final String resource;

    public S3ApiException(S3ErrorCode errorCode, String resource) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.resource = resource;
    }

    public S3ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getResource() {
        return resource;
    }
}
