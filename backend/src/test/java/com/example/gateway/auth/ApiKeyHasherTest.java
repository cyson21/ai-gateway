package com.example.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ApiKeyHasherTest {

    @Test
    void sha256HexMatchesFixedVectors() {
        Map<String, String> vectors = Map.of(
                "The quick brown fox jumps over the lazy dog",
                "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
                "hello",
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");

        vectors.forEach((input, expectedHash) -> assertThat(ApiKeyHasher.sha256Hex(input)).isEqualTo(expectedHash));
    }

    @Test
    void sha256HexSupportsEmptyInput() {
        assertThat(ApiKeyHasher.sha256Hex("")).isEqualTo(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256HexSupportsUnicodeInput() {
        assertThat(ApiKeyHasher.sha256Hex("한글")).isEqualTo(
                "bd87f9bb68b67d2fa1cb82b6751820e946d5b1316d25d5fd96512fb4be44a2a8");
    }

    @Test
    void sha256HexReturnsLowercaseHexAndCorrectLength() {
        String hash = ApiKeyHasher.sha256Hex("hello");
        assertThat(hash).isEqualTo(hash.toLowerCase());
        assertThat(hash).matches("[0-9a-f]+");
        assertThat(hash).hasSize(64);
    }
}
