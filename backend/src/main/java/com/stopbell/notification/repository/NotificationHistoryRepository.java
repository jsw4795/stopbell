package com.stopbell.notification.repository;

import com.stopbell.notification.entity.NotificationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationHistoryRepository extends JpaRepository<NotificationHistory, Long> {
}
