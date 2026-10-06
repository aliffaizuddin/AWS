package dev.cloudlite.s3.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cloudlite.s3.error.S3ErrorCode;
import dev.cloudlite.s3.service.CompletedPart;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CompleteRequestParserTest {

    private static byte[] xml(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesPartsWithAwsNamespaceAndQuotedEtags() {
        var parts = CompleteRequestParser.parse(xml(
            "<CompleteMultipartUpload xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Part><PartNumber>1</PartNumber><ETag>\"aa\"</ETag></Part>"
                + "<Part><PartNumber>2</PartNumber><ETag>bb</ETag></Part>"
                + "</CompleteMultipartUpload>"));

        assertThat(parts).containsExactly(new CompletedPart(1, "\"aa\""), new CompletedPart(2, "bb"));
    }

    @Test
    void parsesASinglePart() {
        var parts = CompleteRequestParser.parse(xml(
            "<CompleteMultipartUpload><Part><PartNumber>7</PartNumber><ETag>x</ETag></Part></CompleteMultipartUpload>"));

        assertThat(parts).containsExactly(new CompletedPart(7, "x"));
    }

    @Test
    void rejectsEmptyBodyNonXmlNoPartsAndIncompleteParts() {
        for (String body : new String[] {
            "",
            "not xml",
            "<CompleteMultipartUpload></CompleteMultipartUpload>",
            "<CompleteMultipartUpload><Part><ETag>x</ETag></Part></CompleteMultipartUpload>",
            "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber></Part></CompleteMultipartUpload>",
            "<CompleteMultipartUpload><Part><PartNumber>abc</PartNumber><ETag>x</ETag></Part></CompleteMultipartUpload>"
        }) {
            assertThatThrownBy(() -> CompleteRequestParser.parse(xml(body)))
                .as("body: %s", body)
                .extracting("errorCode").isEqualTo(S3ErrorCode.MALFORMED_XML);
        }
    }
}
