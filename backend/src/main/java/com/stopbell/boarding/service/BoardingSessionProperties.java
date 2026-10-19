package com.stopbell.boarding.service;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("boarding.session")
public class BoardingSessionProperties {
    private Duration maxLifetime = Duration.ofHours(4);

    public Duration getMaxLifetime() {
        return maxLifetime;
    }

    public void setMaxLifetime(Duration maxLifetime) {
        if (maxLifetime == null || maxLifetime.isNegative() || maxLifetime.isZero()
                || maxLifetime.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("Session lifetime must be positive UTC microseconds");
        }
        // Reject durations which cannot be represented even at the beginning of MySQL DATETIME's range.
        try {
            if (LocalDateTime.of(1000, 1, 1, 0, 0).plus(maxLifetime).getYear() > 9999) {
                throw new IllegalArgumentException("Session lifetime exceeds MySQL DATETIME");
            }
        } catch (java.time.DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException("Session lifetime overflows", exception);
        }
        this.maxLifetime = maxLifetime;
    }
}
