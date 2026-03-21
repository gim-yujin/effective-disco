package com.effectivedisco.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String SECRET =
            "test-secret-key-must-be-at-least-256-bits-long-for-hs256-algorithm";

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 86400000L);
    }

    @Test
    void generateToken_returnsNonBlankToken() {
        String token = provider.generateToken("user1");
        assertThat(token).isNotBlank();
    }

    @Test
    void getUsernameFromToken_returnsOriginalUsername() {
        String token = provider.generateToken("user1");
        assertThat(provider.getUsernameFromToken(token)).isEqualTo("user1");
    }

    @Test
    void validateToken_validToken_returnsTrue() {
        String token = provider.generateToken("user1");
        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_expiredToken_returnsFalse() throws InterruptedException {
        JwtTokenProvider shortLivedProvider = new JwtTokenProvider(SECRET, 1L);
        String token = shortLivedProvider.generateToken("user1");
        Thread.sleep(10);
        assertThat(shortLivedProvider.validateToken(token)).isFalse();
    }

    @Test
    void validateToken_tamperedToken_returnsFalse() {
        String token = provider.generateToken("user1");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    void validateToken_emptyString_returnsFalse() {
        assertThat(provider.validateToken("")).isFalse();
    }

    @Test
    void validateToken_randomString_returnsFalse() {
        assertThat(provider.validateToken("not.a.jwt")).isFalse();
    }
}
