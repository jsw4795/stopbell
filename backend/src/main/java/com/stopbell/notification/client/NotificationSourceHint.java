package com.stopbell.notification.client;

import com.stopbell.notification.entity.BoardingNotificationEventType;
import com.stopbell.notification.entity.NotificationSourceType;
import com.stopbell.transit.domain.TransitEventType;

/** Exactly one source identity; neither source uses sentinel IDs or the other source's event enum. */
public record NotificationSourceHint(NotificationSourceType sourceType, Long alarmId, TransitEventType eventType,
        Long boardingSessionId, BoardingNotificationEventType boardingEventType) {
    public NotificationSourceHint {
        boolean valid = sourceType == NotificationSourceType.ALARM
                ? alarmId != null && alarmId > 0 && eventType != null && boardingSessionId == null && boardingEventType == null
                : sourceType == NotificationSourceType.BOARDING_SESSION && boardingSessionId != null && boardingSessionId > 0
                    && boardingEventType != null && alarmId == null && eventType == null;
        if (!valid) {
            throw new IllegalArgumentException("Invalid notification source identity");
        }
    }

    public static NotificationSourceHint alarm(long id, TransitEventType type) {
        return new NotificationSourceHint(NotificationSourceType.ALARM, id, type, null, null);
    }

    public static NotificationSourceHint boardingSession(long id, BoardingNotificationEventType type) {
        return new NotificationSourceHint(NotificationSourceType.BOARDING_SESSION, null, null, id, type);
    }
}
