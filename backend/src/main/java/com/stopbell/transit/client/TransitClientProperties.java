package com.stopbell.transit.client;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transit.client")
public record TransitClientProperties(
        Duration connectTimeout,
        Duration responseTimeout,
        Duration metadataResponseTimeout,
        Tago tago,
        Seoul seoul
) {

    public TransitClientProperties {
        requirePositive(connectTimeout, "Transit connect timeout");
        requirePositive(responseTimeout, "Transit response timeout");
        requirePositive(metadataResponseTimeout, "Transit metadata response timeout");
        if (tago == null || seoul == null) {
            throw new IllegalArgumentException("Transit provider configuration must not be null");
        }
    }

    @Override
    public String toString() {
        return "TransitClientProperties[redacted]";
    }

    public record Tago(String baseUrl, String metadataBaseUrl, String serviceKey) {

        public Tago {
            requireNotBlank(baseUrl, "TAGO base URL");
            requireNotBlank(metadataBaseUrl, "TAGO metadata base URL");
            requireNotBlank(serviceKey, "TAGO service key");
        }

        @Override
        public String toString() {
            return "Tago[redacted]";
        }
    }

    public record Seoul(String baseUrl, String serviceKey) {

        public Seoul {
            requireNotBlank(baseUrl, "Seoul bus base URL");
            URI uri = URI.create(baseUrl);
            if (!"http".equals(uri.getScheme()) || !"ws.bus.go.kr".equals(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 80)
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
                throw new IllegalArgumentException("Seoul bus base URL must be the official HTTP ws.bus.go.kr origin");
            }
            requireNotBlank(serviceKey, "Seoul bus service key");
        }

        @Override
        public String toString() {
            return "Seoul[redacted]";
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
