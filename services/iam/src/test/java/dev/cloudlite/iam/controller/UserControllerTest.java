package dev.cloudlite.iam.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.iam.domain.User;
import dev.cloudlite.iam.error.GlobalExceptionHandler;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.service.NewUser;
import dev.cloudlite.iam.service.UserService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserController.class)
@Import(GlobalExceptionHandler.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Test
    void createReturns201WithTheRawApiKey() throws Exception {
        User user = new User("alice", "hashed");
        given(userService.create("alice")).willReturn(new NewUser(user, "raw-key-123"));

        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\"}"))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("raw-key-123")));
    }

    @Test
    void createReturns409WhenTheUsernameAlreadyExists() throws Exception {
        given(userService.create("alice")).willThrow(new IamApiException(IamErrorCode.USER_ALREADY_EXISTS));

        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\"}"))
            .andExpect(status().isConflict())
            .andExpect(content().string(containsString("USER_ALREADY_EXISTS")));
    }

    @Test
    void listReturns200WithAJsonBody() throws Exception {
        given(userService.list()).willReturn(List.of(new User("alice", "hashed")));

        mockMvc.perform(get("/users"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("alice")));
    }

    @Test
    void getReturns400WhenTheIdIsNotAValidUuid() throws Exception {
        mockMvc.perform(get("/users/not-a-uuid"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getReturns404WhenTheUserIsMissing() throws Exception {
        UUID id = UUID.randomUUID();
        given(userService.get(id)).willThrow(new IamApiException(IamErrorCode.USER_NOT_FOUND));

        mockMvc.perform(get("/users/" + id))
            .andExpect(status().isNotFound());
    }

    @Test
    void attachRoleReturns204OnSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        mockMvc.perform(post("/users/" + id + "/roles/" + roleId))
            .andExpect(status().isNoContent());
    }

    @Test
    void attachPolicyReturns404WhenThePolicyIsMissing() throws Exception {
        UUID id = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        doThrow(new IamApiException(IamErrorCode.POLICY_NOT_FOUND))
            .when(userService).attachPolicy(id, policyId);

        mockMvc.perform(post("/users/" + id + "/policies/" + policyId))
            .andExpect(status().isNotFound());
    }
}
