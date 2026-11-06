package com.stopbell.user.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.boarding.service.BoardingTrackingRuntime;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Caller holds the User lock and commits the deletion intent with these changes. */
@Service
class AccountDeletionQuiescer {
    private final JdbcTemplate jdbc;
    private final AlarmRepository alarms;
    private final BoardingSessionRepository sessions;
    private final NotificationDeliveryRepository deliveries;
    private final BoardingTrackingRuntime runtime;
    private final Clock clock;

    AccountDeletionQuiescer(JdbcTemplate jdbc, AlarmRepository alarms, BoardingSessionRepository sessions,
            NotificationDeliveryRepository deliveries, BoardingTrackingRuntime runtime, Clock jwtClock) {
        this.jdbc = jdbc; this.alarms = alarms; this.sessions = sessions; this.deliveries = deliveries;
        this.runtime = runtime; this.clock = jwtClock;
    }

    void stop(long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Deletion preparation requires a transaction");
        for (Long id : jdbc.queryForList("select id from alarms where user_id=? order by id", Long.class, userId)) {
            alarms.findByIdForUpdate(id).ifPresent(alarm -> alarm.deactivate());
        }
        for (Long id : jdbc.queryForList("select id from boarding_sessions where user_id=? order by id", Long.class, userId)) {
            sessions.findOwnedForUpdate(id, userId).ifPresent(session -> {
                session.cancel(session.getRevision(), now());
                long revision = session.getRevision();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { runtime.committed(id, revision, false); }
                });
            });
        }
        for (Long id : jdbc.queryForList("""
                select d.id from notification_deliveries d join notification_events e on e.id=d.notification_event_id
                where e.user_id=? order by d.id
                """, Long.class, userId)) {
            deliveries.findByIdForUpdate(id).ifPresent(delivery -> delivery.terminateDispatch(now()));
        }
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
