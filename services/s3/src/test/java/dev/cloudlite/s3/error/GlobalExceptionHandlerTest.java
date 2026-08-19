package dev.cloudlite.s3.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void s3ApiExceptionIsRenderedAsAwsShapedXmlWithTheRightStatus() {
        S3ApiException ex = new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, "photos");

        ResponseEntity<S3ErrorResponse> response = handler.handleS3ApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/xml");
        assertThat(response.getBody().getCode()).isEqualTo("NoSuchBucket");
        assertThat(response.getBody().getResource()).isEqualTo("photos");
        assertThat(response.getBody().getRequestId()).isNotBlank();
    }

    @Test
    void bucketAlreadyExistsMapsTo409() {
        S3ApiException ex = new S3ApiException(S3ErrorCode.BUCKET_ALREADY_EXISTS, "photos");

        ResponseEntity<S3ErrorResponse> response = handler.handleS3ApiException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void noHandlerFoundIsRenderedAsAGenericNotFoundError() {
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/no/such/route", new HttpHeaders());

        ResponseEntity<S3ErrorResponse> response = handler.handleNoHandlerFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo("NotFound");
    }

    @Test
    void methodNotSupportedIsRenderedAs405() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("POST");

        ResponseEntity<S3ErrorResponse> response = handler.handleMethodNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().getCode()).isEqualTo("MethodNotAllowed");
    }

    @Test
    void unexpectedExceptionIsRenderedAsAGenericNonLeakyInternalError() {
        Exception ex = new RuntimeException("column \"foo\" does not exist");

        ResponseEntity<S3ErrorResponse> response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getCode()).isEqualTo("InternalError");
        assertThat(response.getBody().getMessage()).doesNotContain("column");
    }
}
