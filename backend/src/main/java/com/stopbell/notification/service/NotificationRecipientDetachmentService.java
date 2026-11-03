package com.stopbell.notification.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Detaches one recipient in the caller's transaction; does not delete a Device or Event. */
@Service
public class NotificationRecipientDetachmentService {
    private final NotificationDeliveryRepository deliveries;
    private final DeviceRepository devices;
    private final Clock clock;

    public NotificationRecipientDetachmentService(NotificationDeliveryRepository deliveries,
            DeviceRepository devices, @Qualifier("jwtClock") Clock clock) {
        this.deliveries = deliveries;
        this.devices = devices;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean detach(long deliveryId, long expectedOwnerId) {
        var delivery = deliveries.findByIdForUpdate(deliveryId).orElse(null);
        if (delivery == null || delivery.getDevice() == null) {
            return false;
        }
        var device = devices.findByIdForUpdate(delivery.getDevice().getId()).orElse(null);
        if (device == null || !device.getUser().getId().equals(expectedOwnerId)) {
            return false;
        }
        delivery.detachDevice(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return true;
    }
}
