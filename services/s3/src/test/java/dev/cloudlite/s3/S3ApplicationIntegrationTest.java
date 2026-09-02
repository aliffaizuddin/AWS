package dev.cloudlite.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class S3ApplicationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    static Path dataDir;

    private static HttpServer iamStub;
    private static volatile String stubDecision = "ALLOW";
    private static volatile int stubStatusCode = 200;

    @DynamicPropertySource
    static void dataDirProperty(DynamicPropertyRegistry registry) {
        registry.add("s3.data-dir", () -> dataDir.toString());
    }

    @DynamicPropertySource
    static void iamBaseUrlProperty(DynamicPropertyRegistry registry) throws IOException {
        iamStub = HttpServer.create(new InetSocketAddress(0), 0);
        iamStub.createContext("/authorize", exchange -> {
            if (stubStatusCode != 200) {
                exchange.sendResponseHeaders(stubStatusCode, -1);
                exchange.close();
                return;
            }
            byte[] responseBytes =
                ("{\"decision\":\"" + stubDecision + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        iamStub.start();
        registry.add("iam.base-url", () -> "http://localhost:" + iamStub.getAddress().getPort());
    }

    @AfterAll
    static void stopIamStub() {
        if (iamStub != null) {
            iamStub.stop(0);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(S3ApplicationIntegrationTest.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void resetAuthState() {
        stubDecision = "ALLOW";
        stubStatusCode = 200;
        restTemplate.getRestTemplate().getInterceptors().clear();
        restTemplate.getRestTemplate().getInterceptors().add((request, body, execution) -> {
            request.getHeaders().add("Authorization", "Bearer e2e-test-token");
            return execution.execute(request, body);
        });
    }

    @Test
    void healthzReturns200OnceTheAppIsUp() {
        ResponseEntity<Void> response = restTemplate.getForEntity("/healthz", Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void createBucketThenPutGetAndDeleteAnObject() {
        restTemplate.put("/e2e-bucket", null);

        ResponseEntity<Void> head = restTemplate.exchange("/e2e-bucket", HttpMethod.HEAD, null, Void.class);
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<Void> put = restTemplate.exchange(
            "/e2e-bucket/hello.txt", HttpMethod.PUT, new HttpEntity<>("hello world".getBytes(), putHeaders), Void.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getHeaders().getETag()).isNotBlank();
        assertThat(stripQuotes(put.getHeaders().getETag())).isEqualTo("5eb63bbbe01eeed093cb22bb8f5acdc3");

        ResponseEntity<byte[]> get = restTemplate.getForEntity("/e2e-bucket/hello.txt", byte[].class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(get.getBody())).isEqualTo("hello world");
        assertThat(get.getHeaders().getETag()).isEqualTo(put.getHeaders().getETag());
        assertThat(get.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);

        restTemplate.delete("/e2e-bucket/hello.txt");

        ResponseEntity<byte[]> getAfterDelete = restTemplate.getForEntity("/e2e-bucket/hello.txt", byte[].class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        restTemplate.delete("/e2e-bucket");

        ResponseEntity<Void> headAfterBucketDelete =
            restTemplate.exchange("/e2e-bucket", HttpMethod.HEAD, null, Void.class);
        assertThat(headAfterBucketDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void putWithFormUrlEncodedContentTypeStoresTheRealBodyNotAnEmptyOne() {
        restTemplate.put("/e2e-bucket-form", null);

        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        byte[] bodyBytes = "not=actually-form-data".getBytes();
        ResponseEntity<Void> put = restTemplate.exchange(
            "/e2e-bucket-form/payload.txt", HttpMethod.PUT, new HttpEntity<>(bodyBytes, putHeaders), Void.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<byte[]> get = restTemplate.getForEntity("/e2e-bucket-form/payload.txt", byte[].class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).isEqualTo(bodyBytes);

        restTemplate.delete("/e2e-bucket-form/payload.txt");
        restTemplate.delete("/e2e-bucket-form");
    }

    @Test
    void authorizeReturns403WhenTheDecisionIsDeny() {
        stubDecision = "DENY";

        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("AccessDenied");
    }

    @Test
    void authorizeReturns403WhenNoAuthorizationHeaderIsPresent() {
        restTemplate.getRestTemplate().getInterceptors().clear();

        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("AccessDenied");
    }

    @Test
    void authorizeReturns500WhenIamIsUnavailable() {
        stubStatusCode = 500;

        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("InternalError");
    }

    @Test
    void actuatorPrometheusIsReachableWithoutAuthAndTagsMetricsByApplication() {
        restTemplate.getRestTemplate().getInterceptors().clear();

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
        assertThat(response.getBody()).contains("application=\"s3\"");
    }

    @Test
    void logLinesAreJsonFormatted(CapturedOutput output) throws Exception {
        log.info("json-logging-smoke-test-marker");

        String jsonLine = output.getOut().lines()
            .filter(line -> line.contains("json-logging-smoke-test-marker"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("marker line not found in captured output"));

        JsonNode node = new ObjectMapper().readTree(jsonLine);
        assertThat(node.get("message").asText()).isEqualTo("json-logging-smoke-test-marker");
        assertThat(node.has("level")).isTrue();
        assertThat(node.has("logger_name")).isTrue();
    }

    private static String stripQuotes(String etag) {
        return etag.replace("\"", "");
    }
}
