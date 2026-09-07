package com.stopbell.user.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "google")
public record GoogleProperties(String serverClientId) {

    public GoogleProperties {
        if (serverClientId == null || serverClientId.isBlank()) {
            throw new IllegalArgumentException("Google server client ID must not be blank");
        }
    }
}
