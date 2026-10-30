package com.stopbell.notification.client;

import java.time.Instant;

import com.stopbell.notification.entity.Device;
import com.stopbell.transit.domain.TransitEventType;

/** Immutable source-specific Event hints supplied by the worker; contains no persistence behavior. */
public record NotificationSendRequest(
        String fid, NotificationSourceHint source, long notificationEventId, Instant expiresAt, String targetName,
        PassedNotificationContext passedContext
) {
    public NotificationSendRequest {
        Device.validatePushTarget(fid);
        if (source == null || notificationEventId <= 0 || expiresAt == null
                || expiresAt.getEpochSecond() <= 0) {
            throw new IllegalArgumentException("Invalid notification hints");
        }
        if (source.eventType() != TransitEventType.PASSED) {
            passedContext = null;
        }
    }

    public NotificationSendRequest(String fid, NotificationSourceHint source, long eventId, Instant expiresAt,
            String targetName) {
        this(fid, source, eventId, expiresAt, targetName, null);
    }

    public NotificationSendRequest(String fid, NotificationSourceHint source, long eventId, Instant expiresAt) {
        this(fid, source, eventId, expiresAt, null);
    }

    public NotificationSendRequest(String fid, long alarmId, TransitEventType eventType, long notificationEventId, Instant expiresAt) {
        this(fid, NotificationSourceHint.alarm(alarmId, eventType), notificationEventId, expiresAt);
    }

    public Long alarmId() { return source.alarmId(); }
    public TransitEventType eventType() { return source.eventType(); }

    @Override
    public String toString() {
        return "NotificationSendRequest[redacted]";
    }
}
