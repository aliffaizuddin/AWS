package dev.cloudlite.s3.iamclient;

public class IamAccessDeniedException extends RuntimeException {

    public IamAccessDeniedException() {
        super();
    }

    public IamAccessDeniedException(String reason) {
        super(reason);
    }
}
