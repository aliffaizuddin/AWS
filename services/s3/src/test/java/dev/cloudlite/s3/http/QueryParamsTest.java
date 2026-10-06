package dev.cloudlite.s3.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QueryParamsTest {

    @Test
    void detectsValueLessAndValuedParameters() {
        assertThat(QueryParams.has("uploads", "uploads")).isTrue();
        assertThat(QueryParams.has("partNumber=1&uploadId=abc", "uploadId")).isTrue();
        assertThat(QueryParams.has("uploadIdX=1", "uploadId")).isFalse();
        assertThat(QueryParams.has(null, "uploads")).isFalse();
    }

    @Test
    void getReturnsTheDecodedValueOrNull() {
        assertThat(QueryParams.get("partNumber=1&uploadId=a%2Db", "uploadId")).isEqualTo("a-b");
        assertThat(QueryParams.get("uploads", "uploads")).isEmpty();
        assertThat(QueryParams.get("x=1", "uploadId")).isNull();
        assertThat(QueryParams.get(null, "uploadId")).isNull();
    }
}
