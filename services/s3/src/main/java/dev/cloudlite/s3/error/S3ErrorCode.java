package dev.cloudlite.s3.error;

import org.springframework.http.HttpStatus;

public enum S3ErrorCode {
    NO_SUCH_BUCKET("NoSuchBucket", HttpStatus.NOT_FOUND, "The specified bucket does not exist"),
    NO_SUCH_KEY("NoSuchKey", HttpStatus.NOT_FOUND, "The specified key does not exist"),
    BUCKET_ALREADY_EXISTS("BucketAlreadyExists", HttpStatus.CONFLICT, "The requested bucket name is not available"),
    BUCKET_NOT_EMPTY("BucketNotEmpty", HttpStatus.CONFLICT, "The bucket you tried to delete is not empty"),
    INVALID_BUCKET_NAME("InvalidBucketName", HttpStatus.BAD_REQUEST, "The specified bucket name is not valid"),
    METHOD_NOT_ALLOWED("MethodNotAllowed", HttpStatus.METHOD_NOT_ALLOWED, "The specified method is not allowed against this resource"),
    NOT_FOUND("NotFound", HttpStatus.NOT_FOUND, "The specified resource does not exist"),
    ENTITY_TOO_LARGE("EntityTooLarge", HttpStatus.BAD_REQUEST, "Your proposed upload exceeds the maximum allowed size"),
    INVALID_ARGUMENT("InvalidArgument", HttpStatus.BAD_REQUEST, "Invalid Argument"),
    INTERNAL_ERROR("InternalError", HttpStatus.INTERNAL_SERVER_ERROR, "We encountered an internal error. Please try again.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    S3ErrorCode(String code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
