package dev.cloudlite.iam.error;

import org.springframework.http.HttpStatus;

public enum IamErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "The specified user does not exist"),
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, "The specified role does not exist"),
    POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "The specified policy does not exist"),
    USER_ALREADY_EXISTS(HttpStatus.CONFLICT, "A user with this username already exists"),
    ROLE_ALREADY_EXISTS(HttpStatus.CONFLICT, "A role with this name already exists"),
    INVALID_API_KEY(HttpStatus.UNAUTHORIZED, "The supplied API key is invalid"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "The supplied token has expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "The supplied token is invalid"),
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "Invalid Argument"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The specified resource does not exist"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "We encountered an internal error. Please try again.");

    private final HttpStatus status;
    private final String defaultMessage;

    IamErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
