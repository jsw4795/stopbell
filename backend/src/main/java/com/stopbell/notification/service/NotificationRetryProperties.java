package com.stopbell.notification.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Provisional development policy, including finite safety bounds; tune after TASK-710 latency checks. */
@ConfigurationProperties("notification.dispatch.retry")
public record NotificationRetryProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("PT10S") Duration baseRetryDelay,
        @DefaultValue("PT60S") Duration maxRetryDelay,
        @DefaultValue("PT2S") Duration boundedJitter,
        @DefaultValue("PT10S") Duration recoveryDelay
) {
    public NotificationRetryProperties {
        if (maxAttempts < 1 || maxAttempts > 10
                || !bounded(baseRetryDelay, Duration.ofSeconds(10))
                || !bounded(maxRetryDelay, baseRetryDelay)
                || !bounded(boundedJitter, Duration.ZERO) || boundedJitter.compareTo(maxRetryDelay) > 0
                || !bounded(recoveryDelay, baseRetryDelay)) {
            throw new IllegalArgumentException("Invalid bounded notification retry settings");
        }
    }

    private static boolean bounded(Duration value, Duration minimum) {
        return value != null && minimum != null && value.compareTo(minimum) >= 0
                && value.compareTo(Duration.ofMinutes(5)) <= 0 && value.getNano() % 1_000 == 0;
    }
}
