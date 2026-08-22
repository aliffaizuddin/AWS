package dev.cloudlite.s3.iamclient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
            .andExpect(content().json("{\"action\":\"s3:GetObject\",\"resource\":\"arn:cloudlite:s3:::bucket/key\"}"))
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
