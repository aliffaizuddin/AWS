package dev.cloudlite.s3.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import jakarta.servlet.http.HttpServletRequest;
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

    @Autowired
    private ObjectController objectController;

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
    void putRejectsBasedOnDeclaredContentLengthAloneWithoutReadingTheBody() throws Exception {
        // NOTE: this is a direct unit-style call into the controller rather than a MockMvc
        // request. MockHttpServletRequest.getContentLengthLong() in this Spring Test version
        // is derived purely from the actual body bytes set via .content(...) — there is no way
        // to set a declared Content-Length independent of the real body through MockMvc's
        // request builder/header/post-processor API, which is exactly the "declared vs. actual"
        // distinction Fix 4 needs to verify. A Mockito-mocked HttpServletRequest lets us assert
        // the early-rejection guard fires from the declared length alone, and — even more
        // strongly than a MockMvc test could — that the input stream is never even opened.
        given(objectService.maxObjectSize()).willReturn(4L);
        HttpServletRequest request = mock(HttpServletRequest.class);
        given(request.getContentLengthLong()).willReturn(999999999L);

        assertThatThrownBy(() -> objectController.put("photos", "cat.png", "text/plain", request))
            .isInstanceOf(S3ApiException.class)
            .satisfies(ex -> assertThat(((S3ApiException) ex).getErrorCode()).isEqualTo(S3ErrorCode.ENTITY_TOO_LARGE));

        verify(request, never()).getInputStream();
    }

    @Test
    void putWithAnUnparseableContentTypeFallsBackToOctetStreamInsteadOfFailing() throws Exception {
        given(objectService.maxObjectSize()).willReturn(100L * 1024 * 1024);
        given(objectService.put(eq("photos"), eq("cat.png"), any(), eq("application/octet-stream")))
            .willReturn("abc123");

        mockMvc.perform(put("/photos/cat.png")
                .header("Content-Type", "garbage-not-a-mime-type")
                .content("hi".getBytes()))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"abc123\""));
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
