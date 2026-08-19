package dev.cloudlite.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class S3ApplicationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDirProperty(DynamicPropertyRegistry registry) {
        registry.add("s3.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

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

        ResponseEntity<byte[]> get = restTemplate.getForEntity("/e2e-bucket/hello.txt", byte[].class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(get.getBody())).isEqualTo("hello world");
        assertThat(get.getHeaders().getETag()).isEqualTo(put.getHeaders().getETag());

        restTemplate.delete("/e2e-bucket/hello.txt");

        ResponseEntity<byte[]> getAfterDelete = restTemplate.getForEntity("/e2e-bucket/hello.txt", byte[].class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        restTemplate.delete("/e2e-bucket");
    }
}
