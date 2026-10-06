package dev.cloudlite.s3.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.domain.UploadPart;
import dev.cloudlite.s3.error.GlobalExceptionHandler;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.iamclient.AuthInterceptor;
import dev.cloudlite.s3.iamclient.AuthWebMvcConfigurer;
import dev.cloudlite.s3.service.CompletedPart;
import dev.cloudlite.s3.service.CompletionResult;
import dev.cloudlite.s3.service.MultipartService;
import dev.cloudlite.s3.service.ObjectService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = {MultipartController.class, ObjectController.class},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {AuthInterceptor.class, AuthWebMvcConfigurer.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class MultipartControllerTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired private MockMvc mockMvc;
    @MockBean private MultipartService multipartService;
    @MockBean private ObjectService objectService;

    @Test
    void createReturnsInitiateResult() throws Exception {
        given(multipartService.create("photos", "dir/big.bin", "video/mp4"))
            .willReturn(new MultipartUpload(ID, "photos", "dir/big.bin", "video/mp4"));

        mockMvc.perform(post("/photos/dir/big.bin?uploads").header("Content-Type", "video/mp4"))
            .andExpect(status().isOk())
            .andExpect(xpath("/InitiateMultipartUploadResult/Bucket").string("photos"))
            .andExpect(xpath("/InitiateMultipartUploadResult/Key").string("dir/big.bin"))
            .andExpect(xpath("/InitiateMultipartUploadResult/UploadId").string(ID.toString()));
    }

    @Test
    void uploadPartReturnsQuotedEtagAndDoesNotTouchPlainPut() throws Exception {
        given(objectService.maxObjectSize()).willReturn(100L * 1024 * 1024);
        given(multipartService.uploadPart(eq("photos"), eq("big.bin"), eq(ID), eq(3), any())).willReturn("abc");

        mockMvc.perform(put("/photos/big.bin?partNumber=3&uploadId=" + ID).content("hi".getBytes()))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"abc\""));
        verify(objectService, never()).put(any(), any(), any(), any());
    }

    @Test
    void uploadPartWithBadPartNumberIsInvalidArgument() throws Exception {
        mockMvc.perform(put("/photos/big.bin?partNumber=abc&uploadId=" + ID).content("hi".getBytes()))
            .andExpect(status().isBadRequest())
            .andExpect(xpath("/Error/Code").string("InvalidArgument"));
    }

    @Test
    void badUploadIdIsNoSuchUpload() throws Exception {
        mockMvc.perform(delete("/photos/big.bin?uploadId=not-a-uuid"))
            .andExpect(status().isNotFound())
            .andExpect(xpath("/Error/Code").string("NoSuchUpload"));
    }

    @Test
    void putWithOnlyPartNumberIsInvalidArgument() throws Exception {
        mockMvc.perform(put("/photos/big.bin?partNumber=1").content("hi".getBytes()))
            .andExpect(status().isBadRequest())
            .andExpect(xpath("/Error/Code").string("InvalidArgument"));
        verify(objectService, never()).put(any(), any(), any(), any());
    }

    @Test
    void putWithOnlyUploadIdIsInvalidArgument() throws Exception {
        mockMvc.perform(put("/photos/big.bin?uploadId=" + ID).content("hi".getBytes()))
            .andExpect(status().isBadRequest())
            .andExpect(xpath("/Error/Code").string("InvalidArgument"));
        verify(objectService, never()).put(any(), any(), any(), any());
    }

    @Test
    void completeParsesBodyAndReturnsResult() throws Exception {
        given(multipartService.complete("photos", "big.bin", ID,
                List.of(new CompletedPart(1, "\"a\""), new CompletedPart(2, "\"b\""))))
            .willReturn(new CompletionResult("photos", "big.bin", "e-2"));

        mockMvc.perform(post("/photos/big.bin?uploadId=" + ID)
                .contentType("application/x-www-form-urlencoded")
                .content("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>\"a\"</ETag></Part>"
                    + "<Part><PartNumber>2</PartNumber><ETag>\"b\"</ETag></Part></CompleteMultipartUpload>"))
            .andExpect(status().isOk())
            .andExpect(xpath("/CompleteMultipartUploadResult/Location").string("/photos/big.bin"))
            .andExpect(xpath("/CompleteMultipartUploadResult/ETag").string("\"e-2\""));
    }

    @Test
    void completeWithMalformedBodyIsMalformedXml() throws Exception {
        mockMvc.perform(post("/photos/big.bin?uploadId=" + ID).content("nope"))
            .andExpect(status().isBadRequest())
            .andExpect(xpath("/Error/Code").string("MalformedXML"));
    }

    @Test
    void abortReturns204() throws Exception {
        mockMvc.perform(delete("/photos/big.bin?uploadId=" + ID))
            .andExpect(status().isNoContent());
        verify(multipartService).abort("photos", "big.bin", ID);
        verify(objectService, never()).delete(any(), any());
    }

    @Test
    void listPartsReturnsParts() throws Exception {
        given(multipartService.listParts("photos", "big.bin", ID))
            .willReturn(List.of(new UploadPart(ID, 1, UUID.randomUUID(), 5, "aa")));

        mockMvc.perform(get("/photos/big.bin?uploadId=" + ID))
            .andExpect(status().isOk())
            .andExpect(xpath("/ListPartsResult/UploadId").string(ID.toString()))
            .andExpect(xpath("/ListPartsResult/Part[1]/PartNumber").string("1"))
            .andExpect(xpath("/ListPartsResult/Part[1]/ETag").string("\"aa\""))
            .andExpect(xpath("/ListPartsResult/Part[1]/Size").string("5"));
    }

    @Test
    void listUploadsReturnsInProgressUploads() throws Exception {
        given(multipartService.listUploads("photos"))
            .willReturn(List.of(new MultipartUpload(ID, "photos", "big.bin", "text/plain")));

        mockMvc.perform(get("/photos?uploads"))
            .andExpect(status().isOk())
            .andExpect(xpath("/ListMultipartUploadsResult/Bucket").string("photos"))
            .andExpect(xpath("/ListMultipartUploadsResult/Upload[1]/Key").string("big.bin"))
            .andExpect(xpath("/ListMultipartUploadsResult/Upload[1]/UploadId").string(ID.toString()));
    }

    @Test
    void serviceErrorsMapToS3Errors() throws Exception {
        given(multipartService.listParts("photos", "big.bin", ID))
            .willThrow(new S3ApiException(S3ErrorCode.NO_SUCH_UPLOAD, ID.toString()));

        mockMvc.perform(get("/photos/big.bin?uploadId=" + ID))
            .andExpect(status().isNotFound())
            .andExpect(xpath("/Error/Code").string("NoSuchUpload"));
    }
}
