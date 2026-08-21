package dev.cloudlite.iam.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.iam.domain.Policy;
import dev.cloudlite.iam.error.GlobalExceptionHandler;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.policy.Effect;
import dev.cloudlite.iam.policy.PolicyDocument;
import dev.cloudlite.iam.policy.PolicyStatement;
import dev.cloudlite.iam.service.PolicyService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PolicyController.class)
@Import(GlobalExceptionHandler.class)
class PolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PolicyService policyService;

    @Test
    void createReturns201WithTheNewPolicy() throws Exception {
        Policy policy = new Policy("read-only", "{\"statements\":[]}");
        given(policyService.create("read-only", new PolicyDocument(List.of()))).willReturn(policy);
        given(policyService.parseDocument(policy)).willReturn(new PolicyDocument(List.of()));

        mockMvc.perform(post("/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"read-only\",\"document\":{\"statements\":[]}}"))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("read-only")));
    }

    @Test
    void createReturns400WhenTheNameAlreadyExists() throws Exception {
        given(policyService.create("read-only", new PolicyDocument(List.of())))
            .willThrow(new IamApiException(IamErrorCode.INVALID_ARGUMENT));

        mockMvc.perform(post("/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"read-only\",\"document\":{\"statements\":[]}}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listReturns200WithAJsonBody() throws Exception {
        Policy policy = new Policy("read-only", "{\"statements\":[]}");
        given(policyService.list()).willReturn(List.of(policy));
        given(policyService.parseDocument(policy)).willReturn(new PolicyDocument(List.of(
            new PolicyStatement(Effect.ALLOW, List.of("s3:GetObject"), List.of("arn:cloudlite:s3:::b/*")))));

        mockMvc.perform(get("/policies"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("read-only")));
    }

    @Test
    void getReturns404WhenThePolicyIsMissing() throws Exception {
        UUID id = UUID.randomUUID();
        given(policyService.get(id)).willThrow(new IamApiException(IamErrorCode.POLICY_NOT_FOUND));

        mockMvc.perform(get("/policies/" + id)).andExpect(status().isNotFound());
    }
}
