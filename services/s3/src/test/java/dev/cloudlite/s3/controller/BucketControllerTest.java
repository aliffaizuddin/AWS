package dev.cloudlite.s3.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.s3.domain.Bucket;
import dev.cloudlite.s3.error.GlobalExceptionHandler;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.service.BucketService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BucketController.class)
@Import(GlobalExceptionHandler.class)
class BucketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BucketService bucketService;

    @Test
    void createReturns200OnSuccess() throws Exception {
        mockMvc.perform(put("/photos")).andExpect(status().isOk());
    }

    @Test
    void createReturns409WithAnXmlBodyWhenTheBucketAlreadyExists() throws Exception {
        doThrow(new S3ApiException(S3ErrorCode.BUCKET_ALREADY_EXISTS, "photos"))
            .when(bucketService).create("photos");

        mockMvc.perform(put("/photos"))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith("application/xml"))
            .andExpect(content().string(containsString("BucketAlreadyExists")));
    }

    @Test
    void listReturns200WithAnXmlBody() throws Exception {
        given(bucketService.list()).willReturn(List.of(new Bucket("photos")));

        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/xml"))
            .andExpect(content().string(containsString("photos")));
    }

    @Test
    void headReturns200WhenTheBucketExists() throws Exception {
        given(bucketService.exists("photos")).willReturn(true);

        mockMvc.perform(head("/photos")).andExpect(status().isOk());
    }

    @Test
    void headReturns404WithNoBodyWhenTheBucketIsMissing() throws Exception {
        given(bucketService.exists("missing")).willReturn(false);

        mockMvc.perform(head("/missing"))
            .andExpect(status().isNotFound())
            .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void deleteReturns204OnSuccess() throws Exception {
        mockMvc.perform(delete("/photos")).andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns409WhenTheBucketIsNotEmpty() throws Exception {
        doThrow(new S3ApiException(S3ErrorCode.BUCKET_NOT_EMPTY, "photos"))
            .when(bucketService).delete("photos");

        mockMvc.perform(delete("/photos")).andExpect(status().isConflict());
    }
}
