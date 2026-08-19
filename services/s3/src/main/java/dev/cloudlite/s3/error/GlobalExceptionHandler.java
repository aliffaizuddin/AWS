package dev.cloudlite.s3.error;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(S3ApiException.class)
    public ResponseEntity<S3ErrorResponse> handleS3ApiException(S3ApiException ex) {
        return errorResponse(ex.getErrorCode(), ex.getResource());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<S3ErrorResponse> handleNoHandlerFound(NoHandlerFoundException ex) {
        return errorResponse(S3ErrorCode.NOT_FOUND, ex.getRequestURL());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<S3ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return errorResponse(S3ErrorCode.METHOD_NOT_ALLOWED, "");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<S3ErrorResponse> handleUnexpected(Exception ex) {
        log.error("s3: internal error", ex);
        return errorResponse(S3ErrorCode.INTERNAL_ERROR, "");
    }

    private ResponseEntity<S3ErrorResponse> errorResponse(S3ErrorCode code, String resource) {
        S3ErrorResponse body = new S3ErrorResponse(code.code(), code.defaultMessage(), resource, UUID.randomUUID().toString());
        return ResponseEntity.status(code.status())
            .contentType(MediaType.APPLICATION_XML)
            .body(body);
    }
}
