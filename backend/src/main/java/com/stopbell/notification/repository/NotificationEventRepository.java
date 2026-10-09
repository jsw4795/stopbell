package com.stopbell.notification.repository;

import java.util.Optional;

import com.stopbell.notification.entity.NotificationEvent;
import com.stopbell.transit.domain.TransitEventType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<NotificationEvent> findByAlarmIdAndActivationGenerationAndTrackingCycleIdAndEventType(
            Long alarmId, long activationGeneration, String trackingCycleId, TransitEventType eventType
    );
}
