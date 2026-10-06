package dev.cloudlite.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
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
@AutoConfigureObservability
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
    void requestIsRejectedWith503WhenIamIsUnavailable() {
        stubStatusCode = 500;

        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("ServiceUnavailable");
    }

    @Test
    void actuatorPrometheusIsReachableWithoutAuthAndTagsMetricsByApplication() {
        restTemplate.getRestTemplate().getInterceptors().clear();

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
        assertThat(response.getBody()).contains("application=\"s3\"");
        assertThat(response.getBody()).contains("http_server_requests_seconds_bucket");
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

    private static String xmlValue(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private String startUpload(String bucket, String key) {
        ResponseEntity<String> r = restTemplate.postForEntity("/" + bucket + "/" + key + "?uploads", null, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return xmlValue(r.getBody(), "UploadId");
    }

    private String putPart(String bucket, String key, String uploadId, int n, byte[] body) {
        ResponseEntity<Void> r = restTemplate.exchange(
            "/" + bucket + "/" + key + "?partNumber=" + n + "&uploadId=" + uploadId,
            HttpMethod.PUT, new HttpEntity<>(body), Void.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getHeaders().getETag();
    }

    @Test
    void multipartRoundTripReturnsTheConcatenatedBytes() {
        restTemplate.put("/mp-bucket", null);
        byte[] p1 = new byte[5 * 1024 * 1024];
        Arrays.fill(p1, (byte) 'a');
        byte[] p2 = "tail".getBytes(StandardCharsets.UTF_8);
        String id = startUpload("mp-bucket", "dir/big.bin");
        String e1 = putPart("mp-bucket", "dir/big.bin", id, 1, p1);
        String e2 = putPart("mp-bucket", "dir/big.bin", id, 2, p2);

        String listed = restTemplate.getForObject("/mp-bucket?uploads", String.class);
        assertThat(listed).contains(id);
        String parts = restTemplate.getForObject("/mp-bucket/dir/big.bin?uploadId=" + id, String.class);
        assertThat(parts).contains("<PartNumber>1</PartNumber>").contains("<PartNumber>2</PartNumber>");

        String body = "<CompleteMultipartUpload>"
            + "<Part><PartNumber>1</PartNumber><ETag>" + e1 + "</ETag></Part>"
            + "<Part><PartNumber>2</PartNumber><ETag>" + e2 + "</ETag></Part></CompleteMultipartUpload>";
        ResponseEntity<String> done = restTemplate.postForEntity("/mp-bucket/dir/big.bin?uploadId=" + id, body, String.class);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(xmlValue(done.getBody(), "ETag")).endsWith("-2\"");

        ResponseEntity<String> again = restTemplate.postForEntity("/mp-bucket/dir/big.bin?uploadId=" + id, body, String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(xmlValue(again.getBody(), "ETag")).isEqualTo(xmlValue(done.getBody(), "ETag"));

        ResponseEntity<byte[]> got = restTemplate.getForEntity("/mp-bucket/dir/big.bin", byte[].class);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] expected = new byte[p1.length + p2.length];
        System.arraycopy(p1, 0, expected, 0, p1.length);
        System.arraycopy(p2, 0, expected, p1.length, p2.length);
        assertThat(got.getBody()).isEqualTo(expected);

        restTemplate.delete("/mp-bucket/dir/big.bin");
        restTemplate.delete("/mp-bucket");
        assertThat(restTemplate.exchange("/mp-bucket", HttpMethod.HEAD, null, Void.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void abortedUploadDoesNotBlockBucketDelete() {
        restTemplate.put("/abort-bucket", null);
        String id = startUpload("abort-bucket", "k");
        putPart("abort-bucket", "k", id, 1, "x".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> blocked = restTemplate.exchange("/abort-bucket", HttpMethod.DELETE, null, String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        restTemplate.delete("/abort-bucket/k?uploadId=" + id);
        ResponseEntity<String> gone = restTemplate.exchange("/abort-bucket/k?uploadId=" + id, HttpMethod.GET, null, String.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> deleted = restTemplate.exchange("/abort-bucket", HttpMethod.DELETE, null, String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
