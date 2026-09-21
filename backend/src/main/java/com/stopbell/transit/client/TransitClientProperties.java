package com.stopbell.transit.client;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transit.client")
public record TransitClientProperties(
        Duration connectTimeout,
        Duration responseTimeout,
        Tago tago,
        Seoul seoul
) {

    public TransitClientProperties {
        requirePositive(connectTimeout, "Transit connect timeout");
        requirePositive(responseTimeout, "Transit response timeout");
        if (tago == null || seoul == null) {
            throw new IllegalArgumentException("Transit provider configuration must not be null");
        }
    }

    public record Tago(String baseUrl, String serviceKey) {

        public Tago {
            requireNotBlank(baseUrl, "TAGO base URL");
            requireNotBlank(serviceKey, "TAGO service key");
        }
    }

    public record Seoul(String baseUrl, String serviceKey) {

        public Seoul {
            requireNotBlank(baseUrl, "Seoul bus base URL");
            requireNotBlank(serviceKey, "Seoul bus service key");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNotBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
