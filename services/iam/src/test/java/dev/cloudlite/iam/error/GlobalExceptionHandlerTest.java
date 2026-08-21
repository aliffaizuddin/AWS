package dev.cloudlite.iam.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void iamApiExceptionIsRenderedAsJsonWithTheRightStatus() {
        IamApiException ex = new IamApiException(IamErrorCode.USER_NOT_FOUND);

        ResponseEntity<IamErrorResponse> response = handler.handleIamApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
        assertThat(response.getBody().code()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void userAlreadyExistsMapsTo409() {
        IamApiException ex = new IamApiException(IamErrorCode.USER_ALREADY_EXISTS);

        ResponseEntity<IamErrorResponse> response = handler.handleIamApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void invalidApiKeyMapsTo401() {
        IamApiException ex = new IamApiException(IamErrorCode.INVALID_API_KEY);

        ResponseEntity<IamErrorResponse> response = handler.handleIamApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noHandlerFoundIsRenderedAsAGenericNotFoundError() {
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/no/such/route", new HttpHeaders());

        ResponseEntity<IamErrorResponse> response = handler.handleNoHandlerFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void malformedRequestBodyIsRenderedAs400NotA500() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("could not read request body");

        ResponseEntity<IamErrorResponse> response = handler.handleMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void unsupportedMediaTypeIsRenderedAs400NotA500() {
        HttpMediaTypeNotSupportedException ex =
            new HttpMediaTypeNotSupportedException(MediaType.APPLICATION_XML, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<IamErrorResponse> response = handler.handleMediaTypeNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void methodNotSupportedIsRenderedAs405() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("POST");

        ResponseEntity<IamErrorResponse> response = handler.handleMethodNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void typeMismatchIsRenderedAs400NotA500() {
        MethodParameter parameter = mock(MethodParameter.class);
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
            "not-a-uuid", UUID.class, "id", parameter, new IllegalArgumentException("bad uuid"));

        ResponseEntity<IamErrorResponse> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void dataIntegrityViolationIsRenderedAs400NotA500() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("duplicate key value");

        ResponseEntity<IamErrorResponse> response = handler.handleDataIntegrityViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void unexpectedExceptionIsRenderedAsAGenericNonLeakyInternalError() {
        Exception ex = new RuntimeException("column \"foo\" does not exist");

        ResponseEntity<IamErrorResponse> response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).doesNotContain("column");
    }
}
