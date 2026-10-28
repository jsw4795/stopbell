package com.stopbell.notification.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.stopbell.notification.client.NotificationSendRequest;
import com.stopbell.notification.client.NotificationSourceHint;
import com.stopbell.notification.entity.NotificationSourceType;
import com.stopbell.boarding.domain.BoardingTrackingSnapshot;
import com.stopbell.notification.entity.Device;
import com.stopbell.transit.domain.TransitEventType;

/** Current registration, captured under source-specific locks (Session → Delivery → Device or Delivery → Device). Raw FID stays in memory. */
public record NotificationDispatchTarget(
        long deliveryId, long notificationEventId, NotificationSourceHint source,
        long deviceId, long eventOwnerId, long ownershipGeneration, long registrationRevision,
        String fid, LocalDateTime expiresAt, BoardingTrackingSnapshot validatedSession
) {
    public NotificationDispatchTarget {
        Device.validatePushTarget(fid);
        if (deliveryId <= 0 || notificationEventId <= 0 || source == null || deviceId <= 0
                || eventOwnerId <= 0 || ownershipGeneration < 0 || registrationRevision < 0
                || expiresAt == null) {
            throw new IllegalArgumentException("Invalid dispatch target");
        }
        if (source.sourceType() == NotificationSourceType.BOARDING_SESSION
                ? validatedSession == null || !source.boardingSessionId().equals(validatedSession.id())
                    || validatedSession.revision() < 0
                : validatedSession != null) {
            throw new IllegalArgumentException("Invalid Session attempt validation");
        }
    }

    public NotificationDispatchTarget(long deliveryId, long notificationEventId, long alarmId, TransitEventType eventType,
            long deviceId, long eventOwnerId, long ownershipGeneration, long registrationRevision,
            String fid, LocalDateTime expiresAt) {
        this(deliveryId, notificationEventId, NotificationSourceHint.alarm(alarmId, eventType), deviceId, eventOwnerId,
                ownershipGeneration, registrationRevision, fid, expiresAt, null);
    }

    public Long alarmId() { return source.alarmId(); }
    public TransitEventType eventType() { return source.eventType(); }

    public NotificationSendRequest request() {
        return new NotificationSendRequest(fid, source, notificationEventId,
                expiresAt.toInstant(ZoneOffset.UTC), validatedSession == null ? null : validatedSession.targetName());
    }

    @Override
    public String toString() {
        return "NotificationDispatchTarget[redacted]";
    }
}
