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
            throw new IamAccessDeniedException("IAM rejected the token (401)");
        } catch (RestClientException e) {
            throw new IamUnavailableException(e);
        }
        if (body == null || !"ALLOW".equals(body.decision())) {
            throw new IamAccessDeniedException("IAM denied the request");
        }
    }
}
