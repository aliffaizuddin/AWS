package dev.cloudlite.iam.error;

public class IamApiException extends RuntimeException {

    private final IamErrorCode errorCode;

    public IamApiException(IamErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public IamErrorCode getErrorCode() {
        return errorCode;
    }
}
