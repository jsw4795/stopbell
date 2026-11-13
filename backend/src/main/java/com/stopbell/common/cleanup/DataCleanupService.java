package com.stopbell.common.cleanup;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes data that only accumulates. This is an execution entry point only: no schedule is attached;
 * call {@link #cleanup()} from a scheduler or an operator tool once the cadence is decided.
 */
@Service
public class DataCleanupService {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration rotatedRefreshTokenRetention;
    private final Duration notificationRetention;
    private final Duration boardingSessionRetention;

    public DataCleanupService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            Clock jwtClock,
            @Value("${cleanup.batch-size:500}") int batchSize,
            @Value("${cleanup.rotated-refresh-token-retention:PT24H}") Duration rotatedRefreshTokenRetention,
            @Value("${cleanup.notification-retention:P30D}") Duration notificationRetention,
            @Value("${cleanup.boarding-session-retention:P30D}") Duration boardingSessionRetention
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("cleanup batch size must be positive");
        }
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = jwtClock;
        this.batchSize = batchSize;
        this.rotatedRefreshTokenRetention = requirePositive(rotatedRefreshTokenRetention);
        this.notificationRetention = requirePositive(notificationRetention);
        this.boardingSessionRetention = requirePositive(boardingSessionRetention);
    }

    public CleanupResult cleanup() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int refreshTokens = deleteRefreshTokens(now);
        NotificationCounts notifications = deleteNotifications(now.minus(notificationRetention));
        int boardingSessions = deleteBoardingSessions(now.minus(boardingSessionRetention));
        return new CleanupResult(refreshTokens, notifications.events(), notifications.deliveries(), boardingSessions);
    }

    private int deleteRefreshTokens(LocalDateTime now) {
        var params = new MapSqlParameterSource()
                .addValue("now", now)
                .addValue("rotatedCutoff", now.minus(rotatedRefreshTokenRetention))
                .addValue("limit", batchSize);
        return repeat(() -> jdbc.update("""
                DELETE FROM refresh_tokens
                WHERE expires_at <= :now OR rotated_at < :rotatedCutoff
                LIMIT :limit
                """, params));
    }

    /** Events with a PENDING delivery or whose Alarm/Boarding Session is still in progress are kept. */
    private NotificationCounts deleteNotifications(LocalDateTime cutoff) {
        int events = 0;
        int deliveries = 0;
        while (true) {
            int[] deleted = transactions.execute(status -> {
                List<Long> ids = jdbc.queryForList("""
                        SELECT e.id FROM notification_events e
                        WHERE e.created_at < :cutoff
                          AND NOT EXISTS (SELECT 1 FROM notification_deliveries d
                                          WHERE d.notification_event_id = e.id AND d.status = 'PENDING')
                          AND (
                            (e.source_type = 'ALARM' AND NOT EXISTS (
                                SELECT 1 FROM alarms a WHERE a.id = e.alarm_id AND a.status <> 'INACTIVE'))
                            OR (e.source_type = 'BOARDING_SESSION' AND NOT EXISTS (
                                SELECT 1 FROM boarding_sessions b
                                WHERE b.id = e.boarding_session_id AND b.ended_at IS NULL))
                          )
                        ORDER BY e.id
                        LIMIT :limit
                        """,
                        new MapSqlParameterSource("cutoff", cutoff).addValue("limit", batchSize), Long.class);
                if (ids.isEmpty()) {
                    return new int[] {0, 0};
                }
                var idParam = new MapSqlParameterSource("ids", ids);
                int deliveryCount = jdbc.update(
                        "DELETE FROM notification_deliveries WHERE notification_event_id IN (:ids)", idParam);
                int eventCount = jdbc.update("DELETE FROM notification_events WHERE id IN (:ids)", idParam);
                return new int[] {eventCount, deliveryCount};
            });
            if (deleted[0] == 0) {
                return new NotificationCounts(events, deliveries);
            }
            events += deleted[0];
            deliveries += deleted[1];
        }
    }

    /** Only ended Sessions (ended_at set) past retention whose notification events are already gone are deleted. */
    private int deleteBoardingSessions(LocalDateTime cutoff) {
        var params = new MapSqlParameterSource("cutoff", cutoff).addValue("limit", batchSize);
        return repeat(() -> jdbc.update("""
                DELETE FROM boarding_sessions
                WHERE ended_at IS NOT NULL AND ended_at < :cutoff
                  AND NOT EXISTS (SELECT 1 FROM notification_events e WHERE e.boarding_session_id = boarding_sessions.id)
                LIMIT :limit
                """, params));
    }

    private int repeat(java.util.function.IntSupplier batch) {
        int total = 0;
        int deleted;
        do {
            deleted = batch.getAsInt();
            total += deleted;
        } while (deleted >= batchSize);
        return total;
    }

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("cleanup retention must be positive");
        }
        return value;
    }

    public record CleanupResult(int refreshTokens, int notificationEvents, int notificationDeliveries,
                                int boardingSessions) { }

    private record NotificationCounts(int events, int deliveries) { }
}
