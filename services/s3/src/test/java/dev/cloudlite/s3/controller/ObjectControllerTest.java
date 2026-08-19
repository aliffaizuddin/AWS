package dev.cloudlite.s3.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.error.GlobalExceptionHandler;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.service.ObjectService;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ObjectController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ObjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ObjectService objectService;

    @Test
    void putReturns200WithAnEtagHeader() throws Exception {
        given(objectService.maxObjectSize()).willReturn(100L * 1024 * 1024);
        given(objectService.put(eq("photos"), eq("cat.png"), any(), eq("image/png"))).willReturn("abc123");

        mockMvc.perform(put("/photos/cat.png").header("Content-Type", "image/png").content("hi".getBytes()))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"abc123\""));
    }

    @Test
    void putReturns404WhenTheBucketIsMissing() throws Exception {
        given(objectService.maxObjectSize()).willReturn(100L * 1024 * 1024);
        doThrow(new S3ApiException(S3ErrorCode.NO_SUCH_BUCKET, "photos"))
            .when(objectService).put(eq("photos"), eq("cat.png"), any(), any());

        mockMvc.perform(put("/photos/cat.png").content("hi".getBytes()))
            .andExpect(status().isNotFound());
    }

    @Test
    void putReturns400WhenTheBodyExceedsTheMaxObjectSize() throws Exception {
        given(objectService.maxObjectSize()).willReturn(4L);

        mockMvc.perform(put("/photos/cat.png").content("hello world".getBytes()))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("EntityTooLarge")));
    }

    @Test
    void getReturns200WithHeadersAndBody() throws Exception {
        ObjectMetadata metadata = new ObjectMetadata("photos", "cat.png", "image/png", 2L, "abc123", UUID.randomUUID());
        given(objectService.get("photos", "cat.png")).willReturn(metadata);
        given(objectService.getBlob(metadata)).willReturn(new ByteArrayInputStream("hi".getBytes()));

        mockMvc.perform(get("/photos/cat.png"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().string("ETag", "\"abc123\""));
    }

    @Test
    void getReturns404WhenTheKeyIsMissing() throws Exception {
        given(objectService.get("photos", "missing.png"))
            .willThrow(new S3ApiException(S3ErrorCode.NO_SUCH_KEY, "missing.png"));

        mockMvc.perform(get("/photos/missing.png")).andExpect(status().isNotFound());
    }

    @Test
    void headReturns404WithNoBodyWhenTheKeyIsMissing() throws Exception {
        given(objectService.find("photos", "missing.png")).willReturn(Optional.empty());

        mockMvc.perform(head("/photos/missing.png"))
            .andExpect(status().isNotFound())
            .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void deleteAlwaysReturns204() throws Exception {
        mockMvc.perform(delete("/photos/cat.png")).andExpect(status().isNoContent());
    }
}
