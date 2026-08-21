package dev.cloudlite.iam.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IamApiException.class)
    public ResponseEntity<IamErrorResponse> handleIamApiException(IamApiException ex) {
        return errorResponse(ex.getErrorCode());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<IamErrorResponse> handleNoHandlerFound(NoHandlerFoundException ex) {
        return errorResponse(IamErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<IamErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.debug("iam: malformed request body", ex);
        return errorResponse(IamErrorCode.INVALID_ARGUMENT);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<IamErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.debug("iam: unsupported media type", ex);
        return errorResponse(IamErrorCode.INVALID_ARGUMENT);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<IamErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return errorResponse(IamErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<IamErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return errorResponse(IamErrorCode.INVALID_ARGUMENT);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<IamErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.debug("iam: data integrity violation", ex);
        return errorResponse(IamErrorCode.INVALID_ARGUMENT);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<IamErrorResponse> handleUnexpected(Exception ex) {
        log.error("iam: internal error", ex);
        return errorResponse(IamErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<IamErrorResponse> errorResponse(IamErrorCode code) {
        IamErrorResponse body = new IamErrorResponse(code.name(), code.defaultMessage());
        return ResponseEntity.status(code.status())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }
}
