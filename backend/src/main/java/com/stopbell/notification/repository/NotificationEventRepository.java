package com.stopbell.notification.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;

import com.stopbell.notification.entity.NotificationEvent;
import com.stopbell.notification.entity.BoardingNotificationEventType;
import com.stopbell.transit.domain.TransitEventType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select event from NotificationEvent event
            where event.sourceType = 'ALARM' and event.alarmId = :alarmId
              and event.activationGeneration = :activationGeneration
              and event.trackingCycleId = :trackingCycleId and event.eventType = :eventType
            """)
    Optional<NotificationEvent> findByAlarmIdAndActivationGenerationAndTrackingCycleIdAndEventType(
            Long alarmId, long activationGeneration, String trackingCycleId, TransitEventType eventType
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select event from NotificationEvent event
            where event.sourceType = 'BOARDING_SESSION' and event.boardingSessionId = :boardingSessionId
              and event.boardingEventType = :boardingEventType
            order by event.reissueSequence desc
            """)
    List<NotificationEvent> findLatestBoardingEvents(
            Long boardingSessionId, BoardingNotificationEventType boardingEventType, Limit limit
    );

    /** Latest (highest re-issue sequence) Event of the identity. */
    default Optional<NotificationEvent> findByBoardingSessionIdAndBoardingEventType(
            Long boardingSessionId, BoardingNotificationEventType boardingEventType
    ) {
        return findLatestBoardingEvents(boardingSessionId, boardingEventType, Limit.of(1)).stream().findFirst();
    }
}
