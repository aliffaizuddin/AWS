package dev.cloudlite.s3.controller;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import dev.cloudlite.s3.dto.CompleteMultipartUploadXml;
import dev.cloudlite.s3.dto.CompletedPartXml;
import dev.cloudlite.s3.error.S3ApiException;
import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.service.CompletedPart;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Parses the CompleteMultipartUpload body from raw bytes, whatever the
// request's Content-Type (curl -d sends application/x-www-form-urlencoded).
final class CompleteRequestParser {

    private static final XmlMapper XML = new XmlMapper();

    private CompleteRequestParser() {
    }

    static List<CompletedPart> parse(byte[] body) {
        CompleteMultipartUploadXml parsed;
        try {
            parsed = XML.readValue(body, CompleteMultipartUploadXml.class);
        } catch (IOException e) {
            throw malformed();
        }
        if (parsed == null || parsed.getParts() == null || parsed.getParts().isEmpty()) {
            throw malformed();
        }
        List<CompletedPart> parts = new ArrayList<>();
        for (CompletedPartXml p : parsed.getParts()) {
            if (p == null || p.getPartNumber() == null || p.getEtag() == null) {
                throw malformed();
            }
            parts.add(new CompletedPart(p.getPartNumber(), p.getEtag()));
        }
        return parts;
    }

    private static S3ApiException malformed() {
        return new S3ApiException(S3ErrorCode.MALFORMED_XML, "");
    }
}
