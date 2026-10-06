package dev.cloudlite.s3.controller;

import dev.cloudlite.s3.domain.MultipartUpload;
import dev.cloudlite.s3.dto.CompleteMultipartUploadResultXml;
import dev.cloudlite.s3.dto.InitiateMultipartUploadResultXml;
import dev.cloudlite.s3.dto.ListMultipartUploadsResultXml;
import dev.cloudlite.s3.dto.ListPartsResultXml;
import dev.cloudlite.s3.dto.PartXml;
import dev.cloudlite.s3.dto.UploadXml;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.service.CompletionResult;
import dev.cloudlite.s3.service.MultipartService;
import dev.cloudlite.s3.service.ObjectService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Multipart routes are distinguished from plain object routes by query
// parameters; Spring prefers the mapping with more matching params.
@RestController
public class MultipartController {

    private static final long MAX_COMPLETE_BODY = 2L * 1024 * 1024;

    private final MultipartService multipart;
    private final ObjectService objectService;

    public MultipartController(MultipartService multipart, ObjectService objectService) {
        this.multipart = multipart;
        this.objectService = objectService;
    }

    @PostMapping(path = "/{bucket}/{*key}", params = "uploads")
    public ResponseEntity<InitiateMultipartUploadResultXml> create(
            @PathVariable String bucket,
            @PathVariable String key,
            @RequestHeader(value = "Content-Type", required = false) String contentType) {
        MultipartUpload upload = multipart.create(bucket, strip(key), RequestBodies.contentTypeOrNull(contentType));
        return xml(new InitiateMultipartUploadResultXml(bucket, upload.getObjectKey(), upload.getUploadId().toString()));
    }

    @PutMapping(path = "/{bucket}/{*key}", params = {"partNumber", "uploadId"})
    public ResponseEntity<Void> uploadPart(
            @PathVariable String bucket,
            @PathVariable String key,
            @RequestParam("partNumber") String partNumber,
            @RequestParam("uploadId") String uploadId,
            HttpServletRequest request) throws IOException {
        int number = parsePartNumber(partNumber);
        UUID id = parseUploadId(uploadId);
        byte[] body = RequestBodies.readBounded(request, objectService.maxObjectSize());
        String etag = multipart.uploadPart(bucket, strip(key), id, number, body);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, "\"" + etag + "\"").build();
    }

    // A PUT with only one of the two parameters must never fall through to a
    // plain object PUT (which would overwrite the whole object).
    @PutMapping(path = "/{bucket}/{*key}", params = {"partNumber", "!uploadId"})
    public ResponseEntity<Void> partNumberWithoutUploadId() {
        throw new S3ApiException(S3ErrorCode.INVALID_ARGUMENT, "uploadId");
    }

    @PutMapping(path = "/{bucket}/{*key}", params = {"uploadId", "!partNumber"})
    public ResponseEntity<Void> uploadIdWithoutPartNumber() {
        throw new S3ApiException(S3ErrorCode.INVALID_ARGUMENT, "partNumber");
    }

    @PostMapping(path = "/{bucket}/{*key}", params = "uploadId")
    public ResponseEntity<CompleteMultipartUploadResultXml> complete(
            @PathVariable String bucket,
            @PathVariable String key,
            @RequestParam("uploadId") String uploadId,
            HttpServletRequest request) throws IOException {
        UUID id = parseUploadId(uploadId);
        byte[] body = RequestBodies.readBounded(request, MAX_COMPLETE_BODY);
        String k = strip(key);
        CompletionResult result = multipart.complete(bucket, k, id, CompleteRequestParser.parse(body));
        return xml(new CompleteMultipartUploadResultXml("/" + bucket + "/" + k, bucket, k, "\"" + result.etag() + "\""));
    }

    @DeleteMapping(path = "/{bucket}/{*key}", params = "uploadId")
    public ResponseEntity<Void> abort(
            @PathVariable String bucket, @PathVariable String key, @RequestParam("uploadId") String uploadId) {
        multipart.abort(bucket, strip(key), parseUploadId(uploadId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping(path = "/{bucket}/{*key}", params = "uploadId")
    public ResponseEntity<ListPartsResultXml> listParts(
            @PathVariable String bucket, @PathVariable String key, @RequestParam("uploadId") String uploadId) {
        UUID id = parseUploadId(uploadId);
        String k = strip(key);
        var parts = multipart.listParts(bucket, k, id).stream()
            .map(p -> new PartXml(p.getPartNumber(), "\"" + p.getEtag() + "\"", p.getSizeBytes(), p.getUpdatedAt()))
            .toList();
        return xml(new ListPartsResultXml(bucket, k, id.toString(), parts));
    }

    @GetMapping(path = "/{bucket}", params = "uploads")
    public ResponseEntity<ListMultipartUploadsResultXml> listUploads(@PathVariable String bucket) {
        var uploads = multipart.listUploads(bucket).stream()
            .map(u -> new UploadXml(u.getObjectKey(), u.getUploadId().toString(), u.getInitiatedAt()))
            .toList();
        return xml(new ListMultipartUploadsResultXml(bucket, uploads));
    }

    private static <T> ResponseEntity<T> xml(T body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(body);
    }

    private static String strip(String key) {
        return key.startsWith("/") ? key.substring(1) : key;
    }

    private static int parsePartNumber(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new S3ApiException(S3ErrorCode.INVALID_ARGUMENT, "partNumber");
        }
    }

    // AWS answers NoSuchUpload for an upload ID it can't parse.
    private static UUID parseUploadId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new S3ApiException(S3ErrorCode.NO_SUCH_UPLOAD, raw);
        }
    }
}
