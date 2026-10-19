package com.stopbell.notification.service;

import com.stopbell.notification.entity.Device;
import com.stopbell.notification.client.NotificationSourceHint;
import com.stopbell.boarding.domain.BoardingTrackingSnapshot;
import com.stopbell.notification.entity.NotificationDelivery;
import com.stopbell.notification.entity.NotificationEvent;
import com.stopbell.notification.entity.NotificationSourceType;
import com.stopbell.notification.repository.DeviceRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reads only the original recipient. Never locks Alarm/Event or adds recipients. */
public class NotificationRecipientPreflight {
    public enum Rejection { DEVICE_MISSING, OWNER_CHANGED, GENERATION_CHANGED, DISABLED, TARGET_MISSING }

    public record Result(NotificationDispatchTarget target, Rejection rejection) {
    }

    private final DeviceRepository devices;

    public NotificationRecipientPreflight(DeviceRepository devices) {
        this.devices = devices;
    }

    public Result check(NotificationDelivery delivery) {
        return check(delivery, null);
    }

    /** Session validation is supplied only by the Session-first attempt boundary before Device lock. */
    Result check(NotificationDelivery delivery, BoardingTrackingSnapshot validatedSession) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Recipient preflight requires a Delivery transaction");
        }
        NotificationEvent event = delivery.getNotificationEvent();
        if (event.getSourceType() == NotificationSourceType.BOARDING_SESSION
                && (validatedSession == null || !event.getBoardingSessionId().equals(validatedSession.id()))) {
            throw new IllegalStateException("Session preflight requires validated Session-first locks");
        }
        Device device = devices.findByIdForUpdate(delivery.getDevice().getId()).orElse(null);
        if (device == null) {
            return rejected(Rejection.DEVICE_MISSING);
        }
        if (!event.getUser().getId().equals(device.getUser().getId())) {
            return rejected(Rejection.OWNER_CHANGED);
        }
        if (device.getOwnershipGeneration() != delivery.getRecipientOwnershipGeneration()) {
            return rejected(Rejection.GENERATION_CHANGED);
        }
        if (!device.isEnabled()) {
            return rejected(Rejection.DISABLED);
        }
        try {
            Device.validatePushTarget(device.getCurrentPushTargetId());
        } catch (IllegalArgumentException exception) {
            return rejected(Rejection.TARGET_MISSING);
        }
        NotificationSourceHint source = event.getSourceType() == NotificationSourceType.ALARM
                ? NotificationSourceHint.alarm(event.getAlarmId(), event.getEventType())
                : NotificationSourceHint.boardingSession(event.getBoardingSessionId(), event.getBoardingEventType());
        return new Result(new NotificationDispatchTarget(delivery.getId(), event.getId(), source, device.getId(),
                event.getUser().getId(), device.getOwnershipGeneration(),
                device.getRegistrationRevision(), device.getCurrentPushTargetId(), delivery.getExpiresAt(), validatedSession), null);
    }

    private Result rejected(Rejection reason) {
        return new Result(null, reason);
    }
}
