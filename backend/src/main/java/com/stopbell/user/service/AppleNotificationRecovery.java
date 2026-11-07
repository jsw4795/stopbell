package com.stopbell.user.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded retries; event outcome and local deletion commit together. */
@Component
public class AppleNotificationRecovery {
    private static final Logger log = LoggerFactory.getLogger(AppleNotificationRecovery.class);
    private final JdbcTemplate jdbc;
    private final AccountDeletionService deletion;
    private final Clock clock;
    private final boolean enabled;

    public AppleNotificationRecovery(JdbcTemplate jdbc, AccountDeletionService deletion, Clock jwtClock,
            @Value("${apple.notifications.enabled:true}") boolean notificationsEnabled,
            @Value("${apple.notifications.worker-enabled:true}") boolean workerEnabled) {
        this.jdbc = jdbc;
        this.deletion = deletion;
        this.clock = jwtClock;
        this.enabled = notificationsEnabled && workerEnabled;
    }

    @Scheduled(scheduler = "accountDeletionScheduler", initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void recover() {
        if (!enabled) return;
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update("delete from apple_notification_events where state in ('DELETED','SKIPPED') and completed_at <= ?", now.minusDays(30));
        for (byte[] hash : jdbc.query("""
                select jti_hash from apple_notification_events
                where state='PENDING' and (next_attempt_at is null or next_attempt_at <= ?)
                order by coalesce(next_attempt_at,received_at),jti_hash limit 10
                """, (rs, index) -> rs.getBytes(1), now)) {
            try {
                jdbc.update("update apple_notification_events set next_attempt_at=?,attempt_count=attempt_count+1 where jti_hash=? and state='PENDING'",
                        now.plusMinutes(1), hash);
                if (deletion.prepareAppleEvent(hash) == AccountDeletionService.EventResult.READY) {
                    deletion.deleteForAppleEvent(hash);
                }
            } catch (RuntimeException failure) {
                try {
                    jdbc.update("update apple_notification_events set last_error='PROCESSING_FAILED' where jti_hash=? and state='PENDING'", hash);
                } catch (RuntimeException metadataFailure) {
                    log.warn("Apple notification retry metadata unavailable: {}", metadataFailure.getClass().getSimpleName());
                }
                log.warn("Apple notification recovery deferred: {}", failure.getClass().getSimpleName());
            }
        }
    }
}
