package com.stopbell.notification.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("notification.dispatch")
public record NotificationDispatchProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("50") int batchSize,
        @DefaultValue("PT1S") Duration fixedDelay
) {
    public NotificationDispatchProperties {
        if (batchSize <= 0 || fixedDelay == null || fixedDelay.compareTo(Duration.ofMillis(1)) < 0
                || fixedDelay.compareTo(Duration.ofMillis(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Dispatch batch size and millisecond delay must be positive and finite");
        }
    }
}
