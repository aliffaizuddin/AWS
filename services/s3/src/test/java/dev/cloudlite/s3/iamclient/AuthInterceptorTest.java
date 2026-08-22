package dev.cloudlite.s3.iamclient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.s3.controller.BucketController;
import dev.cloudlite.s3.controller.HealthController;
import dev.cloudlite.s3.controller.ObjectController;
import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.error.GlobalExceptionHandler;
import dev.cloudlite.s3.service.BucketService;
import dev.cloudlite.s3.service.ObjectService;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {BucketController.class, ObjectController.class, HealthController.class})
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

    @MockBean
    private DataSource dataSource;

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

    @Test
    void healthzWithNoAuthorizationHeaderStillReturns200WithoutCallingIam() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);

        mockMvc.perform(get("/healthz"))
            .andExpect(status().isOk());

        verify(iamClient, never()).authorize(any(), any(), any());
    }
}
