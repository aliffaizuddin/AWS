package dev.cloudlite.iam.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyGeneratorTest {

    @Test
    void generateProducesANonBlankKey() {
        assertThat(ApiKeyGenerator.generate()).isNotBlank();
    }

    @Test
    void generateProducesDistinctKeysOnEachCall() {
        assertThat(ApiKeyGenerator.generate()).isNotEqualTo(ApiKeyGenerator.generate());
    }

    @Test
    void hashIsDeterministicForTheSameInput() {
        assertThat(ApiKeyGenerator.hash("same-key")).isEqualTo(ApiKeyGenerator.hash("same-key"));
    }

    @Test
    void hashDiffersForDifferentInput() {
        assertThat(ApiKeyGenerator.hash("key-one")).isNotEqualTo(ApiKeyGenerator.hash("key-two"));
    }

    @Test
    void hashNeverReturnsTheRawInput() {
        assertThat(ApiKeyGenerator.hash("my-raw-key")).isNotEqualTo("my-raw-key");
    }
}
