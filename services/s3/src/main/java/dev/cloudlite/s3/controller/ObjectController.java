package dev.cloudlite.s3.controller;

import dev.cloudlite.s3.domain.ObjectMetadata;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.service.ObjectService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ObjectController {

    private static final DateTimeFormatter LAST_MODIFIED_FORMAT =
        DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final ObjectService objectService;

    public ObjectController(ObjectService objectService) {
        this.objectService = objectService;
    }

    @PutMapping("/{bucket}/{*key}")
    public ResponseEntity<Void> put(
            @PathVariable String bucket,
            @PathVariable String key,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            HttpServletRequest request) throws IOException {
        byte[] body = readBoundedBody(request.getInputStream(), objectService.maxObjectSize());
        String etag = objectService.put(bucket, stripLeadingSlash(key), body, contentType);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, "\"" + etag + "\"").build();
    }

    @GetMapping("/{bucket}/{*key}")
    public ResponseEntity<InputStreamResource> get(@PathVariable String bucket, @PathVariable String key) {
        ObjectMetadata metadata = objectService.get(bucket, stripLeadingSlash(key));
        InputStream blob = objectService.getBlob(metadata);
        return ResponseEntity.ok().headers(headersFor(metadata)).body(new InputStreamResource(blob));
    }

    @RequestMapping(path = "/{bucket}/{*key}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(@PathVariable String bucket, @PathVariable String key) {
        return objectService.find(bucket, stripLeadingSlash(key))
            .map(metadata -> ResponseEntity.ok().headers(headersFor(metadata)).<Void>build())
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{bucket}/{*key}")
    public ResponseEntity<Void> delete(@PathVariable String bucket, @PathVariable String key) {
        objectService.delete(bucket, stripLeadingSlash(key));
        return ResponseEntity.noContent().build();
    }

    private HttpHeaders headersFor(ObjectMetadata metadata) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, metadata.getContentType());
        headers.set(HttpHeaders.CONTENT_LENGTH, Long.toString(metadata.getSizeBytes()));
        headers.set(HttpHeaders.ETAG, "\"" + metadata.getEtag() + "\"");
        headers.set(HttpHeaders.LAST_MODIFIED, LAST_MODIFIED_FORMAT.format(metadata.getCreatedAt()));
        return headers;
    }

    private static String stripLeadingSlash(String key) {
        return key.startsWith("/") ? key.substring(1) : key;
    }

    private static byte[] readBoundedBody(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new S3ApiException(S3ErrorCode.ENTITY_TOO_LARGE, "");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
