package dev.cloudlite.iam.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.iam.domain.Role;
import dev.cloudlite.iam.error.GlobalExceptionHandler;
import dev.cloudlite.iam.error.IamApiException;
import dev.cloudlite.iam.error.IamErrorCode;
import dev.cloudlite.iam.service.RoleService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RoleController.class)
@Import(GlobalExceptionHandler.class)
class RoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoleService roleService;

    @Test
    void createReturns201WithTheNewRole() throws Exception {
        given(roleService.create("developers")).willReturn(new Role("developers"));

        mockMvc.perform(post("/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"developers\"}"))
            .andExpect(status().isCreated())
            .andExpect(content().string(containsString("developers")));
    }

    @Test
    void createReturns409WhenTheNameAlreadyExists() throws Exception {
        given(roleService.create("developers")).willThrow(new IamApiException(IamErrorCode.ROLE_ALREADY_EXISTS));

        mockMvc.perform(post("/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"developers\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void listReturns200WithAJsonBody() throws Exception {
        given(roleService.list()).willReturn(List.of(new Role("developers")));

        mockMvc.perform(get("/roles"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("developers")));
    }

    @Test
    void getReturns404WhenTheRoleIsMissing() throws Exception {
        UUID id = UUID.randomUUID();
        given(roleService.get(id)).willThrow(new IamApiException(IamErrorCode.ROLE_NOT_FOUND));

        mockMvc.perform(get("/roles/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void attachPolicyReturns204OnSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();

        mockMvc.perform(post("/roles/" + id + "/policies/" + policyId))
            .andExpect(status().isNoContent());
    }
}
