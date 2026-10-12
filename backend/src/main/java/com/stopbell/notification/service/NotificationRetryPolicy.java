package com.stopbell.notification.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

import com.stopbell.notification.client.FcmClientProperties;
import com.stopbell.notification.client.NotificationSendResult;

/** Calculates DB due times only; no sleeping, provider calls or mutable retry queue. */
public class NotificationRetryPolicy {
    private final NotificationRetryProperties properties;
    private final Duration requestBudget;
    private final LongUnaryOperator jitter;

    public NotificationRetryPolicy(NotificationRetryProperties properties, FcmClientProperties fcm) {
        this(properties, fcm.requestBudget(), bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    NotificationRetryPolicy(NotificationRetryProperties properties, Duration requestBudget, LongUnaryOperator jitter) {
        if (requestBudget == null || requestBudget.compareTo(Duration.ofMillis(1)) < 0
                || requestBudget.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Dispatch requires a bounded FCM request budget");
        }
        this.properties = properties;
        this.requestBudget = requestBudget;
        this.jitter = jitter;
    }

    public int maxAttempts() {
        return properties.maxAttempts();
    }

    public LocalDateTime recoveryAt(LocalDateTime startedAt, LocalDateTime expiresAt) {
        return cappedTime(startedAt, requestBudget.plus(properties.recoveryDelay()), expiresAt);
    }

    public LocalDateTime retryAt(int attemptCount, NotificationSendResult result, LocalDateTime expiresAt) {
        Duration delay = localDelay(attemptCount);
        Duration floor = "QUOTA_EXCEEDED".equals(result.failureCode())
                ? Duration.ofSeconds(60) : Duration.ofSeconds(10);
        if (delay.compareTo(floor) < 0) {
            delay = floor;
        }
        Duration provider = result.minimumRetryDelay();
        if (provider != null && provider.compareTo(delay) > 0) {
            delay = provider;
        }
        return cappedTime(LocalDateTime.ofInstant(result.receivedAt(), ZoneOffset.UTC), delay, expiresAt);
    }

    Duration localDelay(int attemptCount) {
        if (attemptCount <= 0) {
            throw new IllegalArgumentException("Retry requires a started attempt");
        }
        long cap = properties.maxRetryDelay().toNanos();
        long delay = properties.baseRetryDelay().toNanos();
        // Stop at cap rather than shifting/multiplying by unbounded counts.
        for (int count = 1; count < attemptCount && delay < cap; count++) {
            delay = delay > cap / 2 ? cap : Math.min(cap, delay * 2);
        }
        long maximumJitter = properties.boundedJitter().toNanos() / 1_000;
        long extra = maximumJitter == 0 ? 0 : jitter.applyAsLong(maximumJitter + 1);
        if (extra < 0 || extra > maximumJitter) {
            throw new IllegalStateException("Jitter exceeded its configured bound");
        }
        return Duration.ofNanos(Math.min(cap, delay + extra * 1_000));
    }

    private LocalDateTime cappedTime(LocalDateTime start, Duration delay, LocalDateTime expiresAt) {
        // Compare before adding: even a normalized Long.MAX_VALUE Retry-After cannot overflow.
        if (!start.isBefore(expiresAt) || delay.compareTo(Duration.between(start, expiresAt)) >= 0) {
            return expiresAt;
        }
        LocalDateTime candidate = start.plus(delay);
        // Round a Provider sub-microsecond delay up; never send earlier than its minimum.
        LocalDateTime micros = candidate.truncatedTo(ChronoUnit.MICROS);
        if (!candidate.equals(micros)) {
            micros = micros.plusNanos(1_000);
        }
        return micros.isAfter(expiresAt) ? expiresAt : micros;
    }
}
