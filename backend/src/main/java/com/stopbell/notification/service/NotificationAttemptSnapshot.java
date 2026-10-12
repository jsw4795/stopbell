package com.stopbell.notification.service;

import java.time.LocalDateTime;

/** One durable attempt: Delivery ID + count + microsecond start time + registration revision. */
public record NotificationAttemptSnapshot(
        NotificationDispatchTarget target, int attemptCount, LocalDateTime lastAttemptAt
) {
    public NotificationAttemptSnapshot {
        if (target == null || attemptCount <= 0 || lastAttemptAt == null || lastAttemptAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("Invalid durable attempt identity");
        }
    }

    @Override
    public String toString() {
        return "NotificationAttemptSnapshot[redacted]";
    }
}
