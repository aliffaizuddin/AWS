package dev.cloudlite.s3.controller;

import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

final class RequestBodies {

    private RequestBodies() {
    }

    static byte[] readBounded(HttpServletRequest request, long maxBytes) throws IOException {
        if (request.getContentLengthLong() > maxBytes) {
            throw new S3ApiException(S3ErrorCode.ENTITY_TOO_LARGE, "");
        }
        InputStream in = request.getInputStream();
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

    static String contentTypeOrNull(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null; // services default null/blank to application/octet-stream
        }
        try {
            MediaType.parseMediaType(contentType);
            return contentType;
        } catch (InvalidMediaTypeException e) {
            return "application/octet-stream";
        }
    }
}
