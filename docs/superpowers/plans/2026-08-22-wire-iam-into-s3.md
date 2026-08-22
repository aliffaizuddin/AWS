# Wire IAM into S3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every `services/s3` request (except `/healthz`) go through policy evaluation via a new `iamclient` package that calls `services/iam`'s already-built `/authorize` endpoint, fail-closed, rendered through S3's existing AWS-shaped XML error contract.

**Architecture:** A new `dev.cloudlite.s3.iamclient` package (an `IamClient` wrapping Spring's `RestClient`) plus one `AuthInterceptor` (`HandlerInterceptor`, registered via `AuthWebMvcConfigurer`, excluding `/healthz`) sitting in front of the existing, unmodified `BucketController`/`ObjectController`. The interceptor extracts the caller's `Authorization` header, maps the request's method+path to an S3 action string and ARN resource, and calls `iamClient.authorize(...)` — `ALLOW` lets the chain continue, anything else throws, and S3's existing `GlobalExceptionHandler` renders the failure.

**Tech Stack:** Java 21, Spring Boot 3.3.4 (Spring MVC + virtual threads), Spring's `RestClient` (Spring Framework 6.1+), JUnit 5 + Mockito + AssertJ, `MockRestServiceServer` (for `IamClient`'s own tests), JDK's built-in `com.sun.net.httpserver.HttpServer` (for the end-to-end integration test's lightweight IAM stub — no new test dependency needed).

**Spec:** [`docs/superpowers/specs/2026-08-22-wire-iam-into-s3-design.md`](../specs/2026-08-22-wire-iam-into-s3-design.md)

## Global Constraints

- Module: `services/s3` (existing, merged). No changes to `service/`, `repository/`, `storage/`, or `domain/` — this plan only adds a new `iamclient/` package plus one interceptor/configurer pair, one new `S3ErrorCode` value, one new `GlobalExceptionHandler` handler, and config.
- Action/resource mapping is EXACTLY the spec's §6 table (reproduced in Task 3 below) — do not invent new action strings or a different ARN format.
- Error split is EXACTLY: missing/malformed `Authorization` header, or IAM `DENY`, or IAM 401 → 403 `AccessDenied` (new `S3ErrorCode.ACCESS_DENIED`). IAM connection failure/timeout/5xx → the EXISTING `INTERNAL_ERROR`/500 path (no new handler for this case — `IamUnavailableException` propagates to the existing generic `Exception` handler unchanged).
- The three existing `@WebMvcTest` classes (`HealthControllerTest`, `BucketControllerTest`, `ObjectControllerTest`) get exactly one added `excludeFilters` attribute each on their `@WebMvcTest` annotation — no other line in any of their existing `@Test` methods changes.
- `BucketServiceTest`/`ObjectServiceTest` (service-layer unit tests) are not touched at all — they never go through MVC dispatch.
- `RestClient` timeouts use `SimpleClientHttpRequestFactory` (available since early Spring Framework, confirmed compatible with Spring Framework 6.1/Boot 3.3.4) — do NOT use `ClientHttpRequestFactoryBuilder`/`ClientHttpRequestFactorySettings`, which are Spring Boot 3.4+ APIs not available on this project's pinned 3.3.4.
- Every task commits with a Conventional Commit message (`feat|test|build|docs`) per `docs/decisions/0012-commit-and-branch-conventions.md`.

---

## Task 1: `iamclient` package — `IamClient`, exceptions, request/response records

**Files:**
- Create: `services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthorizeRequestBody.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthorizeResponseBody.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/iamclient/IamAccessDeniedException.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/iamclient/IamUnavailableException.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/iamclient/IamClient.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/iamclient/IamClientConfig.java`
- Test: `services/s3/src/test/java/dev/cloudlite/s3/iamclient/IamClientTest.java`

**Interfaces:**
- Produces: `IamClient(RestClient restClient)` constructor, method `authorize(String bearerToken, String action, String resource): void` — returns normally on `ALLOW`, throws `IamAccessDeniedException` on `DENY`/401, throws `IamUnavailableException` (wrapping the cause) on any other error/timeout/5xx. `IamClientConfig` produces a `RestClient` bean named `iamRestClient`, built from `iam.base-url`/`iam.connect-timeout-ms`/`iam.read-timeout-ms` (properties added in Task 5 — until then, reference them by name; Spring will fail to start the full app without them, but this task's own unit test never boots a Spring context, so nothing here blocks on that).
- Consumes: nothing from earlier tasks — this is the first task.

- [ ] **Step 1: Create the request/response records**

`services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthorizeRequestBody.java`:

```java
package dev.cloudlite.s3.iamclient;

public record AuthorizeRequestBody(String action, String resource) {
}
```

`services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthorizeResponseBody.java`:

```java
package dev.cloudlite.s3.iamclient;

public record AuthorizeResponseBody(String decision) {
}
```

- [ ] **Step 2: Create the exception types**

`services/s3/src/main/java/dev/cloudlite/s3/iamclient/IamAccessDeniedException.java`:

```java
package dev.cloudlite.s3.iamclient;

public class IamAccessDeniedException extends RuntimeException {
}
```

`services/s3/src/main/java/dev/cloudlite/s3/iamclient/IamUnavailableException.java`:

```java
package dev.cloudlite.s3.iamclient;

public class IamUnavailableException extends RuntimeException {

    public IamUnavailableException(Throwable cause) {
        super(cause);
    }
}
```

- [ ] **Step 3: Write the failing tests for `IamClient`**

`services/s3/src/test/java/dev/cloudlite/s3/iamclient/IamClientTest.java`:

```java
package dev.cloudlite.s3.iamclient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class IamClientTest {

    private MockRestServiceServer server;
    private IamClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://iam.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new IamClient(builder.build());
    }

    @Test
    void authorizeReturnsNormallyWhenIamAllows() {
        server.expect(requestTo("http://iam.test/authorize"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer good-token"))
            .andRespond(withSuccess("{\"decision\":\"ALLOW\"}", MediaType.APPLICATION_JSON));

        client.authorize("Bearer good-token", "s3:GetObject", "arn:cloudlite:s3:::bucket/key");

        server.verify();
    }

    @Test
    void authorizeThrowsAccessDeniedWhenIamDenies() {
        server.expect(requestTo("http://iam.test/authorize"))
            .andRespond(withSuccess("{\"decision\":\"DENY\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                client.authorize("Bearer good-token", "s3:GetObject", "arn:cloudlite:s3:::bucket/key"))
            .isInstanceOf(IamAccessDeniedException.class);
    }

    @Test
    void authorizeThrowsAccessDeniedWhenIamReturns401() {
        server.expect(requestTo("http://iam.test/authorize"))
            .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() ->
                client.authorize("Bearer bad-token", "s3:GetObject", "arn:cloudlite:s3:::bucket/key"))
            .isInstanceOf(IamAccessDeniedException.class);
    }

    @Test
    void authorizeThrowsUnavailableWhenIamReturns500() {
        server.expect(requestTo("http://iam.test/authorize"))
            .andRespond(withServerError());

        assertThatThrownBy(() ->
                client.authorize("Bearer good-token", "s3:GetObject", "arn:cloudlite:s3:::bucket/key"))
            .isInstanceOf(IamUnavailableException.class);
    }

    @Test
    void authorizeThrowsUnavailableOnConnectionFailure() {
        server.expect(requestTo("http://iam.test/authorize"))
            .andRespond(request -> {
                throw new IOException("connection refused");
            });

        assertThatThrownBy(() ->
                client.authorize("Bearer good-token", "s3:GetObject", "arn:cloudlite:s3:::bucket/key"))
            .isInstanceOf(IamUnavailableException.class);
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `cd services/s3 && mvn -q test -Dtest=IamClientTest`
Expected: FAIL — `IamClient` does not exist (compile error).

- [ ] **Step 5: Implement `IamClient`**

`services/s3/src/main/java/dev/cloudlite/s3/iamclient/IamClient.java`:

```java
package dev.cloudlite.s3.iamclient;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class IamClient {

    private final RestClient restClient;

    public IamClient(RestClient iamRestClient) {
        this.restClient = iamRestClient;
    }

    public void authorize(String bearerToken, String action, String resource) {
        AuthorizeResponseBody body;
        try {
            body = restClient.post()
                .uri("/authorize")
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AuthorizeRequestBody(action, resource))
                .retrieve()
                .body(AuthorizeResponseBody.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new IamAccessDeniedException();
        } catch (RestClientException e) {
            throw new IamUnavailableException(e);
        }
        if (body == null || !"ALLOW".equals(body.decision())) {
            throw new IamAccessDeniedException();
        }
    }
}
```

- [ ] **Step 6: Create `IamClientConfig`**

`services/s3/src/main/java/dev/cloudlite/s3/iamclient/IamClientConfig.java`:

```java
package dev.cloudlite.s3.iamclient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class IamClientConfig {

    @Bean
    public RestClient iamRestClient(
            @Value("${iam.base-url}") String baseUrl,
            @Value("${iam.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${iam.read-timeout-ms}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
```

Note: this task does not yet add `iam.base-url`/`iam.connect-timeout-ms`/`iam.read-timeout-ms` to `application.yml` (that's Task 5) — `IamClientTest` never boots a Spring context, so this doesn't block Step 7 below. The full application will fail to start until Task 5 lands; that's expected and fine mid-plan.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd services/s3 && mvn -q test -Dtest=IamClientTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add services/s3/src/main/java/dev/cloudlite/s3/iamclient services/s3/src/test/java/dev/cloudlite/s3/iamclient
git commit -m "feat: add iamclient package for calling IAM's /authorize endpoint"
```

---

## Task 2: `S3ErrorCode.ACCESS_DENIED` + `GlobalExceptionHandler` extension

**Files:**
- Modify: `services/s3/src/main/java/dev/cloudlite/s3/error/S3ErrorCode.java`
- Modify: `services/s3/src/main/java/dev/cloudlite/s3/error/GlobalExceptionHandler.java`
- Modify: `services/s3/src/test/java/dev/cloudlite/s3/error/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `IamAccessDeniedException` (Task 1).
- Produces: `S3ErrorCode.ACCESS_DENIED` (code `"AccessDenied"`, 403). `GlobalExceptionHandler.handleIamAccessDenied(IamAccessDeniedException): ResponseEntity<S3ErrorResponse>`.

- [ ] **Step 1: Add `ACCESS_DENIED` to `S3ErrorCode`**

In `services/s3/src/main/java/dev/cloudlite/s3/error/S3ErrorCode.java`, add one enum constant. The full enum body becomes:

```java
public enum S3ErrorCode {
    NO_SUCH_BUCKET("NoSuchBucket", HttpStatus.NOT_FOUND, "The specified bucket does not exist"),
    NO_SUCH_KEY("NoSuchKey", HttpStatus.NOT_FOUND, "The specified key does not exist"),
    BUCKET_ALREADY_EXISTS("BucketAlreadyExists", HttpStatus.CONFLICT, "The requested bucket name is not available"),
    BUCKET_NOT_EMPTY("BucketNotEmpty", HttpStatus.CONFLICT, "The bucket you tried to delete is not empty"),
    INVALID_BUCKET_NAME("InvalidBucketName", HttpStatus.BAD_REQUEST, "The specified bucket name is not valid"),
    METHOD_NOT_ALLOWED("MethodNotAllowed", HttpStatus.METHOD_NOT_ALLOWED, "The specified method is not allowed against this resource"),
    NOT_FOUND("NotFound", HttpStatus.NOT_FOUND, "The specified resource does not exist"),
    ENTITY_TOO_LARGE("EntityTooLarge", HttpStatus.BAD_REQUEST, "Your proposed upload exceeds the maximum allowed size"),
    INVALID_ARGUMENT("InvalidArgument", HttpStatus.BAD_REQUEST, "Invalid Argument"),
    ACCESS_DENIED("AccessDenied", HttpStatus.FORBIDDEN, "Access Denied"),
    INTERNAL_ERROR("InternalError", HttpStatus.INTERNAL_SERVER_ERROR, "We encountered an internal error. Please try again.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    S3ErrorCode(String code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
```

(Only the `ACCESS_DENIED(...)` line and its position — right after `INVALID_ARGUMENT`, before `INTERNAL_ERROR` — are new. Everything else in this file is unchanged.)

- [ ] **Step 2: Write the failing test for the new handler**

In `services/s3/src/test/java/dev/cloudlite/s3/error/GlobalExceptionHandlerTest.java`, add this import:

```java
import dev.cloudlite.s3.iamclient.IamAccessDeniedException;
```

and this test method (anywhere among the other `@Test` methods in the class):

```java
    @Test
    void iamAccessDeniedIsRenderedAsAwsShapedXmlAccessDenied() {
        IamAccessDeniedException ex = new IamAccessDeniedException();

        ResponseEntity<S3ErrorResponse> response = handler.handleIamAccessDenied(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/xml");
        assertThat(response.getBody().getCode()).isEqualTo("AccessDenied");
    }
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd services/s3 && mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — `handler.handleIamAccessDenied` does not exist (compile error).

- [ ] **Step 4: Add the handler to `GlobalExceptionHandler`**

In `services/s3/src/main/java/dev/cloudlite/s3/error/GlobalExceptionHandler.java`, add this import:

```java
import dev.cloudlite.s3.iamclient.IamAccessDeniedException;
```

and this handler method (placed among the other `@ExceptionHandler` methods, before the generic `handleUnexpected`):

```java
    @ExceptionHandler(IamAccessDeniedException.class)
    public ResponseEntity<S3ErrorResponse> handleIamAccessDenied(IamAccessDeniedException ex) {
        return errorResponse(S3ErrorCode.ACCESS_DENIED, "");
    }
```

Do not add a handler for `IamUnavailableException` — it is deliberately left to fall through to the existing `handleUnexpected(Exception ex)` catch-all, which already renders `INTERNAL_ERROR`/500 without leaking detail. This is correct as-is; no change needed there.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd services/s3 && mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add services/s3/src/main/java/dev/cloudlite/s3/error services/s3/src/test/java/dev/cloudlite/s3/error
git commit -m "feat: add AccessDenied error code and handler for IAM auth failures"
```

---

## Task 3: `AuthInterceptor` + `AuthWebMvcConfigurer`

**Files:**
- Create: `services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthInterceptor.java`
- Create: `services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthWebMvcConfigurer.java`
- Test: `services/s3/src/test/java/dev/cloudlite/s3/iamclient/AuthInterceptorTest.java`

**Interfaces:**
- Consumes: `IamClient.authorize(String, String, String)` (Task 1); `IamAccessDeniedException`/`IamUnavailableException` (Task 1); `S3ErrorCode.ACCESS_DENIED` + `GlobalExceptionHandler` (Task 2, exercised indirectly through `@Import` in the test).
- Produces: `AuthInterceptor` (a `@Component implements HandlerInterceptor`), `AuthWebMvcConfigurer` (a `@Component implements WebMvcConfigurer`) registering it with `.excludePathPatterns("/healthz")`. Both are picked up automatically by Spring Boot's component scan and by any `@WebMvcTest` slice that does not explicitly exclude them (Task 4 explicitly excludes them from the three existing controller test classes).

The exact action/resource mapping this task implements (from the spec's §6, reproduced here verbatim):

| S3 operation | Method + Path | Action | Resource |
|---|---|---|---|
| CreateBucket | `PUT /{bucket}` | `s3:CreateBucket` | `arn:cloudlite:s3:::{bucket}` |
| ListBuckets | `GET /` | `s3:ListAllMyBuckets` | `arn:cloudlite:s3:::*` |
| HeadBucket | `HEAD /{bucket}` | `s3:ListBucket` | `arn:cloudlite:s3:::{bucket}` |
| DeleteBucket | `DELETE /{bucket}` | `s3:DeleteBucket` | `arn:cloudlite:s3:::{bucket}` |
| PutObject | `PUT /{bucket}/{key}` | `s3:PutObject` | `arn:cloudlite:s3:::{bucket}/{key}` |
| GetObject | `GET /{bucket}/{key}` | `s3:GetObject` | `arn:cloudlite:s3:::{bucket}/{key}` |
| HeadObject | `HEAD /{bucket}/{key}` | `s3:GetObject` | `arn:cloudlite:s3:::{bucket}/{key}` |
| DeleteObject | `DELETE /{bucket}/{key}` | `s3:DeleteObject` | `arn:cloudlite:s3:::{bucket}/{key}` |

- [ ] **Step 1: Create `AuthWebMvcConfigurer`**

`services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthWebMvcConfigurer.java`:

```java
package dev.cloudlite.s3.iamclient;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class AuthWebMvcConfigurer implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public AuthWebMvcConfigurer(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).excludePathPatterns("/healthz");
    }
}
```

- [ ] **Step 2: Write the failing tests for `AuthInterceptor`**

`services/s3/src/test/java/dev/cloudlite/s3/iamclient/AuthInterceptorTest.java`:

```java
package dev.cloudlite.s3.iamclient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.s3.controller.BucketController;
import dev.cloudlite.s3.controller.ObjectController;
import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.error.GlobalExceptionHandler;
import dev.cloudlite.s3.service.BucketService;
import dev.cloudlite.s3.service.ObjectService;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {BucketController.class, ObjectController.class})
@Import(GlobalExceptionHandler.class)
class AuthInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IamClient iamClient;

    @MockBean
    private BucketService bucketService;

    @MockBean
    private ObjectService objectService;

    @Test
    void createBucketCallsIamWithTheRightActionAndResource() throws Exception {
        mockMvc.perform(put("/photos").header("Authorization", "Bearer good-token"))
            .andExpect(status().isOk());

        verify(iamClient).authorize("Bearer good-token", "s3:CreateBucket", "arn:cloudlite:s3:::photos");
    }

    @Test
    void listBucketsCallsIamWithTheWildcardResource() throws Exception {
        given(bucketService.list()).willReturn(List.of());

        mockMvc.perform(get("/").header("Authorization", "Bearer good-token"))
            .andExpect(status().isOk());

        verify(iamClient).authorize("Bearer good-token", "s3:ListAllMyBuckets", "arn:cloudlite:s3:::*");
    }

    @Test
    void headBucketCallsIamWithListBucketAction() throws Exception {
        mockMvc.perform(head("/photos").header("Authorization", "Bearer good-token"))
            .andExpect(status().isNotFound());

        verify(iamClient).authorize("Bearer good-token", "s3:ListBucket", "arn:cloudlite:s3:::photos");
    }

    @Test
    void deleteBucketCallsIamWithDeleteBucketAction() throws Exception {
        mockMvc.perform(delete("/photos").header("Authorization", "Bearer good-token"))
            .andExpect(status().isNoContent());

        verify(iamClient).authorize("Bearer good-token", "s3:DeleteBucket", "arn:cloudlite:s3:::photos");
    }

    @Test
    void putObjectCallsIamWithPutObjectActionAndTheFullKeyResource() throws Exception {
        mockMvc.perform(put("/photos/cat.png").header("Authorization", "Bearer good-token"))
            .andExpect(status().isOk());

        verify(iamClient).authorize("Bearer good-token", "s3:PutObject", "arn:cloudlite:s3:::photos/cat.png");
    }

    @Test
    void getObjectCallsIamWithGetObjectAction() throws Exception {
        ObjectMetadata metadata = new ObjectMetadata("photos", "cat.png", "image/png", 3L, "abc", UUID.randomUUID());
        given(objectService.get("photos", "cat.png")).willReturn(metadata);
        given(objectService.getBlob(metadata)).willReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

        mockMvc.perform(get("/photos/cat.png").header("Authorization", "Bearer good-token"))
            .andExpect(status().isOk());

        verify(iamClient).authorize("Bearer good-token", "s3:GetObject", "arn:cloudlite:s3:::photos/cat.png");
    }

    @Test
    void headObjectCallsIamWithGetObjectAction() throws Exception {
        given(objectService.find("photos", "cat.png")).willReturn(Optional.empty());

        mockMvc.perform(head("/photos/cat.png").header("Authorization", "Bearer good-token"))
            .andExpect(status().isNotFound());

        verify(iamClient).authorize("Bearer good-token", "s3:GetObject", "arn:cloudlite:s3:::photos/cat.png");
    }

    @Test
    void deleteObjectCallsIamWithDeleteObjectAction() throws Exception {
        mockMvc.perform(delete("/photos/cat.png").header("Authorization", "Bearer good-token"))
            .andExpect(status().isNoContent());

        verify(iamClient).authorize("Bearer good-token", "s3:DeleteObject", "arn:cloudlite:s3:::photos/cat.png");
    }

    @Test
    void missingAuthorizationHeaderReturns403WithoutCallingIam() throws Exception {
        mockMvc.perform(put("/photos"))
            .andExpect(status().isForbidden());

        verify(iamClient, never()).authorize(any(), any(), any());
    }

    @Test
    void malformedAuthorizationHeaderReturns403WithoutCallingIam() throws Exception {
        mockMvc.perform(put("/photos").header("Authorization", "not-a-bearer-token"))
            .andExpect(status().isForbidden());

        verify(iamClient, never()).authorize(any(), any(), any());
    }

    @Test
    void iamDenyReturns403() throws Exception {
        doThrow(new IamAccessDeniedException())
            .when(iamClient).authorize("Bearer good-token", "s3:CreateBucket", "arn:cloudlite:s3:::photos");

        mockMvc.perform(put("/photos").header("Authorization", "Bearer good-token"))
            .andExpect(status().isForbidden());
    }

    @Test
    void iamUnavailableReturns500() throws Exception {
        doThrow(new IamUnavailableException(new RuntimeException("connection refused")))
            .when(iamClient).authorize("Bearer good-token", "s3:CreateBucket", "arn:cloudlite:s3:::photos");

        mockMvc.perform(put("/photos").header("Authorization", "Bearer good-token"))
            .andExpect(status().isInternalServerError());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd services/s3 && mvn -q test -Dtest=AuthInterceptorTest`
Expected: FAIL — `AuthInterceptor` does not exist (compile error).

- [ ] **Step 4: Implement `AuthInterceptor`**

`services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthInterceptor.java`:

```java
package dev.cloudlite.s3.iamclient;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final IamClient iamClient;

    public AuthInterceptor(IamClient iamClient) {
        this.iamClient = iamClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IamAccessDeniedException();
        }

        @SuppressWarnings("unchecked")
        Map<String, String> pathVariables =
            (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String bucket = pathVariables != null ? pathVariables.get("bucket") : null;
        String key = pathVariables != null ? pathVariables.get("key") : null;
        String method = request.getMethod();

        String action;
        String resource;
        if (bucket == null) {
            action = "s3:ListAllMyBuckets";
            resource = "arn:cloudlite:s3:::*";
        } else if (key == null) {
            resource = "arn:cloudlite:s3:::" + bucket;
            action = switch (method) {
                case "PUT" -> "s3:CreateBucket";
                case "HEAD" -> "s3:ListBucket";
                case "DELETE" -> "s3:DeleteBucket";
                default -> throw new IamAccessDeniedException();
            };
        } else {
            String strippedKey = key.startsWith("/") ? key.substring(1) : key;
            resource = "arn:cloudlite:s3:::" + bucket + "/" + strippedKey;
            action = switch (method) {
                case "PUT" -> "s3:PutObject";
                case "GET" -> "s3:GetObject";
                case "HEAD" -> "s3:GetObject";
                case "DELETE" -> "s3:DeleteObject";
                default -> throw new IamAccessDeniedException();
            };
        }

        iamClient.authorize(authorization, action, resource);
        return true;
    }
}
```

Note the `key`-stripping logic (`key.startsWith("/") ? key.substring(1) : key`) is deliberately identical to `ObjectController.stripLeadingSlash` — this ensures the resource ARN sent to IAM matches the actual object key `ObjectService` operates on, not a slash-prefixed variant. The `default -> throw new IamAccessDeniedException()` arm in each `switch` exists only to satisfy Java's exhaustiveness requirement for a `switch` expression over `String` — it's unreachable in practice, since `BucketController`/`ObjectController`'s own `@RequestMapping`s only ever dispatch the methods each `switch` already lists.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd services/s3 && mvn -q test -Dtest=AuthInterceptorTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthInterceptor.java \
  services/s3/src/main/java/dev/cloudlite/s3/iamclient/AuthWebMvcConfigurer.java \
  services/s3/src/test/java/dev/cloudlite/s3/iamclient/AuthInterceptorTest.java
git commit -m "feat: add AuthInterceptor enforcing IAM policy evaluation on every S3 request"
```

---

## Task 4: Exclude `AuthInterceptor`/`AuthWebMvcConfigurer` from the 3 existing `@WebMvcTest` classes

**Files:**
- Modify: `services/s3/src/test/java/dev/cloudlite/s3/controller/HealthControllerTest.java`
- Modify: `services/s3/src/test/java/dev/cloudlite/s3/controller/BucketControllerTest.java`
- Modify: `services/s3/src/test/java/dev/cloudlite/s3/controller/ObjectControllerTest.java`

**Interfaces:**
- Consumes: `AuthInterceptor`/`AuthWebMvcConfigurer` (Task 3) — by name only, to exclude them from each slice's component scan.
- Produces: nothing later tasks depend on.

Why this task exists: `AuthInterceptor` is a `HandlerInterceptor` and `AuthWebMvcConfigurer` is a `WebMvcConfigurer` — both are among the bean types Spring Boot's `@WebMvcTest` auto-detects and constructs even in a narrow slice. Without this change, all three existing test classes would fail to start their `ApplicationContext` (constructing `AuthWebMvcConfigurer` requires an `AuthInterceptor` bean, which requires an `IamClient` bean, which none of these three slices provide). Excluding both types from each slice's component scan means neither bean is ever constructed there, and every existing `@Test` method in these three files is otherwise completely unchanged.

- [ ] **Step 1: Update `HealthControllerTest`**

In `services/s3/src/test/java/dev/cloudlite/s3/controller/HealthControllerTest.java`, add these two imports:

```java
import dev.cloudlite.s3.iamclient.AuthInterceptor;
import dev.cloudlite.s3.iamclient.AuthWebMvcConfigurer;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
```

and change the class annotation from:

```java
@WebMvcTest(HealthController.class)
class HealthControllerTest {
```

to:

```java
@WebMvcTest(
    controllers = HealthController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {AuthInterceptor.class, AuthWebMvcConfigurer.class}))
class HealthControllerTest {
```

Nothing else in this file changes.

- [ ] **Step 2: Update `BucketControllerTest`**

In `services/s3/src/test/java/dev/cloudlite/s3/controller/BucketControllerTest.java`, add these two imports (alongside the existing ones):

```java
import dev.cloudlite.s3.iamclient.AuthInterceptor;
import dev.cloudlite.s3.iamclient.AuthWebMvcConfigurer;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
```

and change the class annotation from:

```java
@WebMvcTest(BucketController.class)
@Import(GlobalExceptionHandler.class)
class BucketControllerTest {
```

to:

```java
@WebMvcTest(
    controllers = BucketController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {AuthInterceptor.class, AuthWebMvcConfigurer.class}))
@Import(GlobalExceptionHandler.class)
class BucketControllerTest {
```

Nothing else in this file changes.

- [ ] **Step 3: Update `ObjectControllerTest`**

In `services/s3/src/test/java/dev/cloudlite/s3/controller/ObjectControllerTest.java`, add these two imports (alongside the existing ones):

```java
import dev.cloudlite.s3.iamclient.AuthInterceptor;
import dev.cloudlite.s3.iamclient.AuthWebMvcConfigurer;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
```

and change the class annotation from:

```java
@WebMvcTest(ObjectController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ObjectControllerTest {
```

to:

```java
@WebMvcTest(
    controllers = ObjectController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {AuthInterceptor.class, AuthWebMvcConfigurer.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ObjectControllerTest {
```

Nothing else in this file changes — `@AutoConfigureMockMvc(addFilters = false)` (which disables *servlet* `Filter` beans, unrelated to `HandlerInterceptor`s) stays exactly as it was.

- [ ] **Step 4: Run the full existing test suite to confirm nothing else broke**

Run: `cd services/s3 && mvn -q test -Dtest=HealthControllerTest,BucketControllerTest,ObjectControllerTest`
Expected: PASS — every existing `@Test` method in these three files still passes, unmodified.

- [ ] **Step 5: Commit**

```bash
git add services/s3/src/test/java/dev/cloudlite/s3/controller/HealthControllerTest.java \
  services/s3/src/test/java/dev/cloudlite/s3/controller/BucketControllerTest.java \
  services/s3/src/test/java/dev/cloudlite/s3/controller/ObjectControllerTest.java
git commit -m "test: exclude AuthInterceptor from the existing controller test slices"
```

---

## Task 5: Config — `application.yml` + `docker-compose.yml`

**Files:**
- Modify: `services/s3/src/main/resources/application.yml`
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: `iam.base-url`/`iam.connect-timeout-ms`/`iam.read-timeout-ms` are read by `IamClientConfig` (Task 1) — this task is what actually supplies them, unblocking the full application (as opposed to just this plan's own tests) from starting.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Add IAM config to `services/s3/src/main/resources/application.yml`**

Add this block at the end of the file (after the existing `s3:` block):

```yaml
iam:
  base-url: ${IAM_BASE_URL:http://localhost:8081}
  connect-timeout-ms: 2000
  read-timeout-ms: 3000
```

The full file becomes:

```yaml
server:
  port: ${SERVER_PORT:8080}

spring:
  application:
    name: s3
  threads:
    virtual:
      enabled: true
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/cloudlite}
    username: ${SPRING_DATASOURCE_USERNAME:cloudlite}
    password: ${SPRING_DATASOURCE_PASSWORD:cloudlite}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 0
  mvc:
    throw-exception-if-no-handler-found: true
    formcontent:
      filter:
        enabled: false
  web:
    resources:
      add-mappings: false
  servlet:
    multipart:
      enabled: false

s3:
  data-dir: ${S3_DATA_DIR:/data}

iam:
  base-url: ${IAM_BASE_URL:http://localhost:8081}
  connect-timeout-ms: 2000
  read-timeout-ms: 3000
```

- [ ] **Step 2: Add `IAM_BASE_URL` to the `s3` service in `docker-compose.yml`**

In the root `docker-compose.yml`, add one environment variable to the existing `s3` service's `environment:` block. The full file becomes:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: cloudlite
      POSTGRES_USER: cloudlite
      POSTGRES_PASSWORD: cloudlite
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U cloudlite"]
      interval: 5s
      timeout: 5s
      retries: 5

  s3:
    build:
      context: ./services/s3
    environment:
      SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres:5432/cloudlite"
      SPRING_DATASOURCE_USERNAME: "cloudlite"
      SPRING_DATASOURCE_PASSWORD: "cloudlite"
      S3_DATA_DIR: "/data"
      SERVER_PORT: "8080"
      IAM_BASE_URL: "http://iam:8081"
      JAVA_TOOL_OPTIONS: "-Xmx768m"
    ports:
      - "8080:8080"
    volumes:
      - s3-data:/data
    depends_on:
      postgres:
        condition: service_healthy

  iam:
    build:
      context: ./services/iam
    environment:
      SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres:5432/cloudlite"
      SPRING_DATASOURCE_USERNAME: "cloudlite"
      SPRING_DATASOURCE_PASSWORD: "cloudlite"
      IAM_JWT_SECRET: "dev-only-insecure-jwt-signing-secret-please-change"
      IAM_JWT_EXPIRY_SECONDS: "900"
      SERVER_PORT: "8081"
      JAVA_TOOL_OPTIONS: "-Xmx768m"
    ports:
      - "8081:8081"
    depends_on:
      postgres:
        condition: service_healthy

volumes:
  postgres-data:
  s3-data:
```

(Only the `IAM_BASE_URL: "http://iam:8081"` line under `s3`'s `environment:` block is new — everything else, including the `iam` service block itself, already existed.)

- [ ] **Step 3: Verify the full application starts**

Run: `cd services/s3 && mvn -q test` — with `iam.base-url`/`iam.connect-timeout-ms`/`iam.read-timeout-ms` now present in `application.yml`, the full test suite (including any test that boots the whole Spring context) can resolve `IamClientConfig`'s `@Value` placeholders. This is the first point in the plan where the full application context is guaranteed startable end-to-end.
Expected: PASS (all tests from Tasks 1-4, plus the two remaining pre-existing test classes not touched by this plan — `BucketServiceTest`, `ObjectServiceTest`, `S3ApplicationIntegrationTest` — which have not yet been exercised against the new interceptor; Task 6 handles `S3ApplicationIntegrationTest` specifically).

- [ ] **Step 4: Commit**

```bash
git add services/s3/src/main/resources/application.yml docker-compose.yml
git commit -m "build: add IAM connection config for s3"
```

---

## Task 6: End-to-end integration test + `docs/services/s3.md` update

**Files:**
- Modify: `services/s3/src/test/java/dev/cloudlite/s3/S3ApplicationIntegrationTest.java`
- Modify: `docs/services/s3.md`

**Interfaces:**
- Consumes: the full stack from Tasks 1-5.
- Produces: nothing later tasks depend on — this is the last task in the plan.

**Design note for this task:** `S3ApplicationIntegrationTest` is a full `@SpringBootTest` (not a slice) — it boots the real `AuthInterceptor`/`AuthWebMvcConfigurer`/`IamClient` beans, so its two existing tests (which currently make unauthenticated calls) would start failing 403 once this plan's interceptor is active, unless something supplies a token and something answers IAM's role. Rather than modify those two tests' bodies, this task adds: (1) a tiny JDK-built-in `com.sun.net.httpserver.HttpServer` standing in for IAM (started inside a new `@DynamicPropertySource` method, mirroring how the existing `dataDirProperty` method already supplies `s3.data-dir` before context startup — its assigned port is known synchronously right after `.start()`, so it can feed `iam.base-url` the same way); (2) a `@BeforeEach` that installs a `ClientHttpRequestInterceptor` on the shared `TestRestTemplate` bean, transparently adding a `Bearer` header to every outgoing test request. Because both are class-level scaffolding, not per-test logic, the two EXISTING test methods' bodies do not change at all. Two NEW test methods are added for the auth-specific scenarios; the underlying stub's canned decision is only set to `DENY` inside the one test that needs it, and reset to `ALLOW` before every test via the same `@BeforeEach`.

- [ ] **Step 1: Replace `S3ApplicationIntegrationTest.java`**

Replace the entire file with:

```java
package dev.cloudlite.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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

    private static HttpServer iamStub;
    private static volatile String stubDecision = "ALLOW";

    @DynamicPropertySource
    static void dataDirProperty(DynamicPropertyRegistry registry) {
        registry.add("s3.data-dir", () -> dataDir.toString());
    }

    @DynamicPropertySource
    static void iamBaseUrlProperty(DynamicPropertyRegistry registry) throws IOException {
        iamStub = HttpServer.create(new InetSocketAddress(0), 0);
        iamStub.createContext("/authorize", exchange -> {
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

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void resetAuthState() {
        stubDecision = "ALLOW";
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

    private static String stripQuotes(String etag) {
        return etag.replace("\"", "");
    }
}
```

Note: `createBucketThenPutGetAndDeleteAnObject` and
`putWithFormUrlEncodedContentTypeStoresTheRealBodyNotAnEmptyOne` are
byte-for-byte identical to the pre-existing versions of these two
methods — only class-level scaffolding was added around them.

- [ ] **Step 2: Run the full test suite**

Run: `cd services/s3 && mvn -q test`
Expected: PASS — every test from Tasks 1-6, including this integration test (requires Docker for Testcontainers; does not require a real IAM service, since the stub stands in for it).

- [ ] **Step 3: Update `docs/services/s3.md`**

Replace the `## Dependencies` and `## Build/test notes` sections. The relevant part of the file becomes:

```markdown
## Dependencies

- Calls out to the IAM service on every request (except `/healthz`)
  via the `iamclient` package for policy evaluation (see
  [`iam.md`](iam.md)) — wired in per `architecture.md` §11 step 3. See
  [`../superpowers/plans/2026-08-22-wire-iam-into-s3.md`](../superpowers/plans/2026-08-22-wire-iam-into-s3.md)
  for what was built and
  [`../superpowers/specs/2026-08-22-wire-iam-into-s3-design.md`](../superpowers/specs/2026-08-22-wire-iam-into-s3-design.md)
  for the design. Fails closed: if IAM is unreachable, times out, or
  errors, the request is rejected (500 `InternalError`), never
  allowed through.

## Build/test notes

Per `architecture.md` §11: built and tested standalone first (no
auth), then IAM wired in as step 3 — every request now requires a
valid `Authorization: Bearer <jwt>` (obtained from IAM's own
`/auth/token`) except `/healthz`, which remains exempt from the first
commit (`architecture.md` §10).
```

(The `## Scope`, `## Tech stack`, `## Storage`, and `## Out of scope` sections are unchanged. The `**Status:**` line at the top of the file is also unchanged — Phase 1's scope status is independent of this auth-wiring step.)

- [ ] **Step 4: Commit**

```bash
git add services/s3/src/test/java/dev/cloudlite/s3/S3ApplicationIntegrationTest.java docs/services/s3.md
git commit -m "test: extend the end-to-end integration test with IAM auth coverage, update s3 service doc"
```

