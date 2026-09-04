package com.stopbell.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenExpiration) {

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret must not be blank");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer must not be blank");
        }
        if (accessTokenExpiration == null || accessTokenExpiration.isZero() || accessTokenExpiration.isNegative()) {
            throw new IllegalArgumentException("JWT access token expiration must be positive");
        }
    }
}
