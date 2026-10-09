package com.stopbell.notification.repository;

import java.time.LocalDateTime;
import java.util.List;

import com.stopbell.notification.entity.NotificationDelivery;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    List<NotificationDelivery> findAllByNotificationEventIdOrderByIdAsc(Long notificationEventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select delivery from NotificationDelivery delivery
            where delivery.notificationEvent.alarmId = :alarmId and delivery.status = 'PENDING'
            order by delivery.id
            """)
    List<NotificationDelivery> findPendingByAlarmIdForUpdate(Long alarmId);

    @Query("""
            select delivery.id from NotificationDelivery delivery
            where delivery.status = 'PENDING' and delivery.nextAttemptAt <= :now
            order by delivery.nextAttemptAt, delivery.id
            """)
    List<Long> findDuePendingIds(LocalDateTime now, Pageable pageable);
}
