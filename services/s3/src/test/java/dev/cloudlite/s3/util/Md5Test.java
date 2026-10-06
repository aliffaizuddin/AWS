package dev.cloudlite.s3.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class Md5Test {

    @Test
    void hexMatchesKnownDigest() {
        assertThat(Md5.hex("hello".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo("5d41402abc4b2a76b9719d911017c592");
    }

    @Test
    void multipartEtagIsMd5OfConcatenatedBinaryDigestsWithPartCount() {
        // md5("hello"), md5("world") -> md5(bin||bin) + "-2", computed independently with Python hashlib.
        String etag = Md5.multipartEtag(List.of(
            "5d41402abc4b2a76b9719d911017c592",
            "7d793037a0760186574b0282f2f435e7"));

        assertThat(etag).isEqualTo("065947336a2f2a95ba8899f3675c3be6-2");
    }
}
