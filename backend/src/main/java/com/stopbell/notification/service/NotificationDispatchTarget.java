package com.stopbell.notification.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.stopbell.notification.client.NotificationSendRequest;
import com.stopbell.notification.entity.Device;
import com.stopbell.transit.domain.TransitEventType;

/** Current registration, captured under Delivery → Device locks. Raw FID stays in memory. */
public record NotificationDispatchTarget(
        long deliveryId, long notificationEventId, long alarmId, TransitEventType eventType,
        long deviceId, long eventOwnerId, long ownershipGeneration, long registrationRevision,
        String fid, LocalDateTime expiresAt
) {
    public NotificationDispatchTarget {
        Device.validatePushTarget(fid);
        if (deliveryId <= 0 || notificationEventId <= 0 || alarmId <= 0 || deviceId <= 0
                || eventOwnerId <= 0 || ownershipGeneration < 0 || registrationRevision < 0
                || eventType == null || expiresAt == null) {
            throw new IllegalArgumentException("Invalid dispatch target");
        }
    }

    public NotificationSendRequest request() {
        return new NotificationSendRequest(fid, alarmId, eventType, notificationEventId,
                expiresAt.toInstant(ZoneOffset.UTC));
    }

    @Override
    public String toString() {
        return "NotificationDispatchTarget[redacted]";
    }
}
