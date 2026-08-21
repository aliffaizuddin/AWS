package dev.cloudlite.iam.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.iam.error.GlobalExceptionHandler;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.service.AuthService;
import dev.cloudlite.iam.service.TokenResult;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Test
    void issueTokenReturns200WithATokenBody() throws Exception {
        given(authService.issueToken("raw-key")).willReturn(new TokenResult("signed-jwt", 900));

        mockMvc.perform(post("/auth/token").header("Authorization", "ApiKey raw-key"))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("signed-jwt")));
    }

    @Test
    void issueTokenReturns401WhenTheApiKeyIsInvalid() throws Exception {
        given(authService.issueToken("bad-key")).willThrow(new IamApiException(IamErrorCode.INVALID_API_KEY));

        mockMvc.perform(post("/auth/token").header("Authorization", "ApiKey bad-key"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void issueTokenReturns401WhenTheAuthorizationHeaderIsMissingTheApiKeyPrefix() throws Exception {
        mockMvc.perform(post("/auth/token").header("Authorization", "Bearer something"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void issueTokenReturns401WhenTheAuthorizationHeaderIsAbsentEntirely() throws Exception {
        mockMvc.perform(post("/auth/token")).andExpect(status().isUnauthorized());
    }
}
