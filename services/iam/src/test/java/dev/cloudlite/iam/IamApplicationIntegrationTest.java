package dev.cloudlite.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cloudlite.iam.dto.AuthorizeResponse;
import dev.cloudlite.iam.dto.CreatePolicyRequest;
import dev.cloudlite.iam.dto.CreatedUserResponse;
import dev.cloudlite.iam.dto.PolicyResponse;
import dev.cloudlite.iam.dto.RoleResponse;
import dev.cloudlite.iam.dto.TokenResponse;
import dev.cloudlite.iam.policy.Effect;
import dev.cloudlite.iam.policy.PolicyDocument;
import dev.cloudlite.iam.policy.PolicyStatement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class IamApplicationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    private static final Logger log = LoggerFactory.getLogger(IamApplicationIntegrationTest.class);

    @Test
    void healthzReturns200OnceTheAppIsUp() {
        ResponseEntity<Void> response = restTemplate.getForEntity("/healthz", Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void createUserAttachPolicyIssueTokenThenAuthorizeAllowAndDeny() {
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<CreatedUserResponse> createUser = restTemplate.postForEntity(
            "/users", new HttpEntity<>("{\"username\":\"e2e-alice\"}", jsonHeaders), CreatedUserResponse.class);
        assertThat(createUser.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String apiKey = createUser.getBody().apiKey();

        PolicyDocument document = new PolicyDocument(List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::e2e-bucket/*")),
            new PolicyStatement(Effect.DENY, List.of("s3:DeleteObject"), List.of("arn:cloudlite:s3:::e2e-bucket/*"))));
        CreatePolicyRequest createPolicyRequest = new CreatePolicyRequest("e2e-read-only", document);
        ResponseEntity<PolicyResponse> createPolicy = restTemplate.postForEntity(
            "/policies", new HttpEntity<>(createPolicyRequest, jsonHeaders), PolicyResponse.class);
        assertThat(createPolicy.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        restTemplate.postForEntity(
            "/users/" + createUser.getBody().id() + "/policies/" + createPolicy.getBody().id(), null, Void.class);

        HttpHeaders apiKeyHeaders = new HttpHeaders();
        apiKeyHeaders.set("Authorization", "ApiKey " + apiKey);
        ResponseEntity<TokenResponse> tokenResponse = restTemplate.postForEntity(
            "/auth/token", new HttpEntity<>(null, apiKeyHeaders), TokenResponse.class);
        assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String jwt = tokenResponse.getBody().token();

        HttpHeaders bearerHeaders = new HttpHeaders();
        bearerHeaders.set("Authorization", "Bearer " + jwt);
        bearerHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<AuthorizeResponse> allowResponse = restTemplate.postForEntity(
            "/authorize",
            new HttpEntity<>(
                "{\"action\":\"s3:GetObject\",\"resource\":\"arn:cloudlite:s3:::e2e-bucket/file.txt\"}", bearerHeaders),
            AuthorizeResponse.class);
        assertThat(allowResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowResponse.getBody().decision()).isEqualTo("ALLOW");

        ResponseEntity<AuthorizeResponse> denyResponse = restTemplate.postForEntity(
            "/authorize",
            new HttpEntity<>(
                "{\"action\":\"s3:DeleteObject\",\"resource\":\"arn:cloudlite:s3:::e2e-bucket/file.txt\"}", bearerHeaders),
            AuthorizeResponse.class);
        assertThat(denyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(denyResponse.getBody().decision()).isEqualTo("DENY");

        ResponseEntity<AuthorizeResponse> implicitDenyResponse = restTemplate.postForEntity(
            "/authorize",
            new HttpEntity<>(
                "{\"action\":\"s3:PutObject\",\"resource\":\"arn:cloudlite:s3:::other-bucket/file.txt\"}", bearerHeaders),
            AuthorizeResponse.class);
        assertThat(implicitDenyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(implicitDenyResponse.getBody().decision()).isEqualTo("DENY");
    }

    @Test
    void createRoleAttachRolePolicyAttachUserToRoleThenAuthorizeAllowsViaTheRolePath() {
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<CreatedUserResponse> createUser = restTemplate.postForEntity(
            "/users", new HttpEntity<>("{\"username\":\"e2e-bob\"}", jsonHeaders), CreatedUserResponse.class);
        assertThat(createUser.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String apiKey = createUser.getBody().apiKey();

        PolicyDocument roleDocument = new PolicyDocument(List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:ListBucket"), List.of("arn:cloudlite:s3:::e2e-role-bucket"))));
        CreatePolicyRequest createRolePolicyRequest = new CreatePolicyRequest("e2e-role-policy", roleDocument);
        ResponseEntity<PolicyResponse> createRolePolicy = restTemplate.postForEntity(
            "/policies", new HttpEntity<>(createRolePolicyRequest, jsonHeaders), PolicyResponse.class);
        assertThat(createRolePolicy.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<RoleResponse> createRole = restTemplate.postForEntity(
            "/roles", new HttpEntity<>("{\"name\":\"e2e-role\"}", jsonHeaders), RoleResponse.class);
        assertThat(createRole.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        restTemplate.postForEntity(
            "/roles/" + createRole.getBody().id() + "/policies/" + createRolePolicy.getBody().id(),
            null, Void.class);

        restTemplate.postForEntity(
            "/users/" + createUser.getBody().id() + "/roles/" + createRole.getBody().id(), null, Void.class);

        HttpHeaders apiKeyHeaders = new HttpHeaders();
        apiKeyHeaders.set("Authorization", "ApiKey " + apiKey);
        ResponseEntity<TokenResponse> tokenResponse = restTemplate.postForEntity(
            "/auth/token", new HttpEntity<>(null, apiKeyHeaders), TokenResponse.class);
        assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String jwt = tokenResponse.getBody().token();

        HttpHeaders bearerHeaders = new HttpHeaders();
        bearerHeaders.set("Authorization", "Bearer " + jwt);
        bearerHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<AuthorizeResponse> allowResponse = restTemplate.postForEntity(
            "/authorize",
            new HttpEntity<>(
                "{\"action\":\"s3:ListBucket\",\"resource\":\"arn:cloudlite:s3:::e2e-role-bucket\"}", bearerHeaders),
            AuthorizeResponse.class);
        assertThat(allowResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowResponse.getBody().decision()).isEqualTo("ALLOW");
    }

    @Test
    void actuatorPrometheusIsReachableAndTagsMetricsByApplication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
        assertThat(response.getBody()).contains("application=\"iam\"");
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
}
