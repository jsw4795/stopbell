package com.stopbell.notification.client;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("notification.fcm")
public record FcmClientProperties(
        boolean enabled, String projectId, String credentialPath,
        Duration connectTimeout, Duration readTimeout, Duration requestBudget
) {
    // Missing/invalid FCM configuration is handled lazily, without failing Backend startup.
    public boolean configured() {
        return enabled && projectId != null && projectId.matches("[a-z][a-z0-9-]{4,61}[a-z0-9]")
                && credentialPath != null && !credentialPath.isBlank()
                && finite(connectTimeout) && finite(readTimeout) && finite(requestBudget);
    }

    private static boolean finite(Duration value) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0
                && value.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0;
    }

    @Override
    public String toString() {
        return "FcmClientProperties[redacted]";
    }
}
