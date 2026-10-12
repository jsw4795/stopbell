package com.stopbell.notification.client;

import java.time.Instant;

import com.stopbell.notification.entity.Device;
import com.stopbell.transit.domain.TransitEventType;

/** Immutable Event hints supplied by the future worker; contains no persistence behavior. */
public record NotificationSendRequest(
        String fid, long alarmId, TransitEventType eventType, long notificationEventId, Instant expiresAt
) {
    public NotificationSendRequest {
        Device.validatePushTarget(fid);
        if (alarmId <= 0 || notificationEventId <= 0 || eventType == null || expiresAt == null
                || expiresAt.getEpochSecond() <= 0) {
            throw new IllegalArgumentException("Invalid notification hints");
        }
    }

    @Override
    public String toString() {
        return "NotificationSendRequest[redacted]";
    }
}
