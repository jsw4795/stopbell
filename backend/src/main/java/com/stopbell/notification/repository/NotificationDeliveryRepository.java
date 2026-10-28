package com.stopbell.notification.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.stopbell.notification.entity.NotificationDelivery;
import com.stopbell.notification.entity.NotificationSourceType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from NotificationDelivery delivery where delivery.id = :id")
    Optional<NotificationDelivery> findByIdForUpdate(Long id);

    List<NotificationDelivery> findAllByNotificationEventIdOrderByIdAsc(Long notificationEventId);

    /** Current status read after the caller's Session -> Event locks, independent of an older read view. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select delivery from NotificationDelivery delivery where delivery.notificationEvent.id = :eventId order by delivery.id")
    List<NotificationDelivery> findBoardingEventDeliveriesForRead(Long eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select delivery from NotificationDelivery delivery
            where delivery.notificationEvent.sourceType = 'ALARM'
              and delivery.notificationEvent.alarmId = :alarmId and delivery.status = 'PENDING'
            order by delivery.id
            """)
    List<NotificationDelivery> findPendingByAlarmIdForUpdate(Long alarmId);

    /** Caller first locks the exact Session Event; keep this current read free of Event joins. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select delivery from NotificationDelivery delivery
            where delivery.notificationEvent.id = :eventId and delivery.status = 'PENDING'
            order by delivery.id
            """)
    List<NotificationDelivery> findPendingByBoardingEventIdForUpdate(Long eventId);

    interface SourceIdentity {
        Long getEventId();
        NotificationSourceType getSourceType();
        Long getBoardingSessionId();
    }

    /** Immutable hints only, outside the actual attempt transaction/persistence context. */
    @Query("""
            select event.id as eventId, event.sourceType as sourceType, event.boardingSessionId as boardingSessionId
            from NotificationDelivery delivery join delivery.notificationEvent event where delivery.id = :id
            """)
    Optional<SourceIdentity> findSourceIdentity(Long id);

    /** ALARM retains its independent bounded due budget. */
    @Query("""
            select delivery.id from NotificationDelivery delivery
            where delivery.notificationEvent.sourceType = 'ALARM'
              and delivery.status = 'PENDING' and delivery.nextAttemptAt <= :now
            order by delivery.nextAttemptAt, delivery.id
            """)
    List<Long> findDuePendingIds(LocalDateTime now, Pageable pageable);

    /** Expiry never depends on readiness or nextAttemptAt. */
    @Query("""
            select delivery.id from NotificationDelivery delivery
            where delivery.status = 'PENDING' and delivery.expiresAt <= :now
            order by delivery.expiresAt, delivery.id
            """)
    List<Long> findExpiredPendingIds(LocalDateTime now, Pageable pageable);

    /** Independent expiry budgets prevent either source's backlog starving the other. */
    @Query("""
            select delivery.id from NotificationDelivery delivery
            where delivery.notificationEvent.sourceType = :source
              and delivery.status = 'PENDING' and delivery.expiresAt <= :now
            order by delivery.expiresAt, delivery.id
            """)
    List<Long> findExpiredPendingIdsBySource(NotificationSourceType source, LocalDateTime now, Pageable pageable);

    @Query("""
            select delivery.id from NotificationDelivery delivery
            join delivery.notificationEvent event
            join BoardingSession session on session.id = event.boardingSessionId
            where event.sourceType = 'BOARDING_SESSION'
              and ((event.boardingEventType = 'PREPARE_ALIGHT' and event.boardingSessionId in :readyIds
                    and session.status = 'TRACKING' and session.prepareNotificationEnabled = true)
                or (event.boardingEventType = 'DESTINATION_REACHED' and session.status = 'COMPLETED'
                    and session.notificationOffsetStops is null))
              and session.expiresAt > :now
              and session.user.id = event.user.id
              and delivery.status = 'PENDING' and delivery.nextAttemptAt <= :now and delivery.expiresAt > :now
            order by delivery.nextAttemptAt, delivery.id
            """)
    List<Long> findBoardingDuePendingIds(List<Long> readyIds, LocalDateTime now, Pageable pageable);
}
