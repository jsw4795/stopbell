package com.stopbell.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtConfigurationTest {

    private final JwtConfiguration jwtConfiguration = new JwtConfiguration();

    @Test
    @DisplayName("32 bytes 이상 JWT Secret으로 HS256 SecretKey를 생성할 수 있다")
    void create_secret_key_with_at_least_32_bytes() {
        SecretKey secretKey = jwtConfiguration.jwtSecretKey(jwtProperties("a".repeat(32)));

        assertThat(secretKey.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(secretKey.getEncoded()).hasSize(32);
    }

    @Test
    @DisplayName("32 bytes 미만 JWT Secret으로 HS256 SecretKey를 생성하면 실패한다")
    void reject_secret_key_with_less_than_32_bytes() {
        assertThatThrownBy(() -> jwtConfiguration.jwtSecretKey(jwtProperties("a".repeat(31))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT secret must be at least 256 bits for HS256");
    }

    private JwtProperties jwtProperties(String secret) {
        return new JwtProperties(secret, "stopbell", Duration.ofHours(1));
    }
}
