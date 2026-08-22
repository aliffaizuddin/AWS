package dev.cloudlite.iam.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.iam.error.GlobalExceptionHandler;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.policy.Decision;
import dev.cloudlite.iam.service.AuthService;
import dev.cloudlite.iam.service.AuthorizationService;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthorizationController.class)
@Import(GlobalExceptionHandler.class)
class AuthorizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private AuthorizationService authorizationService;

    @Test
    void authorizeReturns200WithAllowWhenThePolicyEngineAllows() throws Exception {
        UUID userId = UUID.randomUUID();
        given(authService.parseUserId("good-jwt")).willReturn(userId);
        given(authorizationService.authorize(userId, "s3:GetObject", "arn:cloudlite:s3:::b/key"))
            .willReturn(Decision.ALLOW);

        mockMvc.perform(post("/authorize")
                .header("Authorization", "Bearer good-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"s3:GetObject\",\"resource\":\"arn:cloudlite:s3:::b/key\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("ALLOW")));
    }

    @Test
    void authorizeReturns401WhenTheTokenIsExpired() throws Exception {
        given(authService.parseUserId("expired-jwt")).willThrow(new IamApiException(IamErrorCode.TOKEN_EXPIRED));

        mockMvc.perform(post("/authorize")
                .header("Authorization", "Bearer expired-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"s3:GetObject\",\"resource\":\"arn:cloudlite:s3:::b/key\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authorizeReturns401WhenTheAuthorizationHeaderIsMissingTheBearerPrefix() throws Exception {
        mockMvc.perform(post("/authorize")
                .header("Authorization", "ApiKey something")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"s3:GetObject\",\"resource\":\"arn:cloudlite:s3:::b/key\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authorizeReturns401WhenTheAuthorizationHeaderIsAbsentEntirely() throws Exception {
        mockMvc.perform(post("/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"s3:GetObject\",\"resource\":\"arn:cloudlite:s3:::b/key\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authorizeReturns400WhenTheActionIsBlank() throws Exception {
        UUID userId = UUID.randomUUID();
        given(authService.parseUserId("good-jwt")).willReturn(userId);

        mockMvc.perform(post("/authorize")
                .header("Authorization", "Bearer good-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"  \",\"resource\":\"arn:cloudlite:s3:::b/key\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void authorizeReturns400WhenTheResourceIsMissing() throws Exception {
        UUID userId = UUID.randomUUID();
        given(authService.parseUserId("good-jwt")).willReturn(userId);

        mockMvc.perform(post("/authorize")
                .header("Authorization", "Bearer good-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"s3:GetObject\"}"))
            .andExpect(status().isBadRequest());
    }
}
