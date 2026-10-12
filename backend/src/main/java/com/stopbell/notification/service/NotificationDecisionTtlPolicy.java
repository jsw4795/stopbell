package com.stopbell.notification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.stopbell.transit.domain.TransitEventType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Provisional development freshness windows; final operational values belong to TASK-709. */
@Component
public class NotificationDecisionTtlPolicy {
    private final Map<TransitEventType, Duration> ttl;

    public NotificationDecisionTtlPolicy(
            @Value("${notification.decision.ttl.one-stop-before:PT60S}") Duration before,
            @Value("${notification.decision.ttl.arrived:PT60S}") Duration arrived,
            @Value("${notification.decision.ttl.passed:PT60S}") Duration passed,
            @Value("${notification.decision.ttl.one-stop-after:PT60S}") Duration after
    ) {
        ttl = Map.of(TransitEventType.ONE_STOP_BEFORE, valid(before), TransitEventType.ARRIVED, valid(arrived),
                TransitEventType.PASSED, valid(passed), TransitEventType.ONE_STOP_AFTER, valid(after));
    }

    public Instant expiresAt(TransitEventType type, Instant observedAt) {
        return observedAt.plus(ttl.get(type));
    }

    private static Duration valid(Duration duration) {
        // Reject overflow and sub-microsecond windows that disappear in DATETIME(6).
        if (duration == null || duration.isNegative() || duration.isZero()
                || duration.toNanos() < 1_000) {
            throw new IllegalArgumentException("Notification TTL must be positive and finite at microsecond precision");
        }
        return duration;
    }
}
