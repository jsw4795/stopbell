package com.stopbell.common.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.stopbell.common.cleanup.DataCleanupService.CleanupResult;
import com.stopbell.support.SharedMySqlExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("db")
@Tag("isolated-db")
@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "cleanup.batch-size=2")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DataCleanupServiceIntegrationTest {

    private static final LocalDateTime OLD = LocalDateTime.now(ZoneOffset.UTC).minusDays(100);
    private static final LocalDateTime NOW = LocalDateTime.now(ZoneOffset.UTC);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        SharedMySqlExtension.database(registry, "data_cleanup");
    }

    @Autowired private DataCleanupService cleanup;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("보존 기간이 지난 종료 데이터만 batch로 삭제하고 진행 중 데이터와 관련 레코드는 보존한다")
    void cleanup_deletes_only_expired_finished_data() {
        long user = insertUser();
        long device = insertDevice(user);

        // Refresh tokens: 3 expired + 1 long-rotated are removed (batch size 2), live and recently rotated stay.
        for (int i = 0; i < 3; i++) {
            insertToken(user, OLD, null);
        }
        insertToken(user, NOW.plusDays(10), OLD);
        long liveToken = insertToken(user, NOW.plusDays(10), null);
        long recentlyRotated = insertToken(user, NOW.plusDays(10), NOW.minusSeconds(5));

        // Alarm events: INACTIVE alarm events removed (3, with deliveries); ACTIVE alarm events and fresh stay.
        long inactiveAlarm = insertAlarm(user, "INACTIVE");
        long activeAlarm = insertAlarm(user, "ACTIVE");
        for (int i = 0; i < 3; i++) {
            insertAlarmEvent(user, inactiveAlarm, OLD, "ACCEPTED", device);
        }
        long activeEvent = insertAlarmEvent(user, activeAlarm, OLD, "ACCEPTED", device);
        long freshEvent = insertAlarmEvent(user, inactiveAlarm, NOW, "ACCEPTED", device);
        long pendingEvent = insertAlarmEvent(user, inactiveAlarm, OLD, "PENDING", device);

        // Boarding sessions: ended+old session with an old event is removed together; open and recent stay.
        long endedOld = insertSession(user, "COMPLETED", OLD);
        long endedOldEvent = insertBoardingEvent(user, endedOld, OLD, "EXPIRED", device);
        long open = insertSession(user, "TRACKING", null);
        long openEvent = insertBoardingEvent(user, open, OLD, "ACCEPTED", device);
        long endedRecent = insertSession(user, "CANCELLED", NOW);

        CleanupResult result = cleanup.cleanup();

        assertThat(result.refreshTokens()).isEqualTo(4);
        assertThat(ids("refresh_tokens")).containsExactlyInAnyOrder(liveToken, recentlyRotated);
        assertThat(result.notificationEvents()).isEqualTo(4);
        assertThat(result.notificationDeliveries()).isEqualTo(4);
        assertThat(ids("notification_events")).containsExactlyInAnyOrder(activeEvent, freshEvent, pendingEvent, openEvent);
        assertThat(jdbc.queryForObject("select count(*) from notification_deliveries", Long.class)).isEqualTo(4);
        assertThat(endedOldEvent).isNotEqualTo(openEvent);
        assertThat(result.boardingSessions()).isEqualTo(1);
        assertThat(ids("boarding_sessions")).containsExactlyInAnyOrder(open, endedRecent);

        CleanupResult again = cleanup.cleanup();
        assertThat(again).isEqualTo(new CleanupResult(0, 0, 0, 0));
    }

    private java.util.List<Long> ids(String table) {
        return jdbc.queryForList("select id from " + table, Long.class);
    }

    private long insertUser() {
        jdbc.update("insert into users (auth_provider, provider_user_id, created_at, updated_at) values ('GOOGLE', ?, ?, ?)",
                UUID.randomUUID().toString(), NOW, NOW);
        return jdbc.queryForObject("select max(id) from users", Long.class);
    }

    private long insertDevice(long user) {
        jdbc.update("""
                insert into devices (user_id, installation_id, installation_credential_hash, platform,
                    current_push_target_id, registration_revision, ownership_generation, enabled,
                    last_registered_at, created_at, updated_at)
                values (?, ?, ?, 'IOS', 'target', 1, 1, true, ?, ?, ?)
                """, user, UUID.randomUUID().toString(), "a".repeat(64), NOW, NOW, NOW);
        return jdbc.queryForObject("select max(id) from devices", Long.class);
    }

    private long insertToken(long user, LocalDateTime expiresAt, LocalDateTime rotatedAt) {
        jdbc.update("insert into refresh_tokens (user_id, token_hash, expires_at, created_at, rotated_at) values (?, ?, ?, ?, ?)",
                user, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                expiresAt, NOW, rotatedAt);
        return jdbc.queryForObject("select max(id) from refresh_tokens", Long.class);
    }

    private long insertAlarm(long user, String status) {
        jdbc.update("insert into alarms (user_id, transit_type, status, created_at, updated_at) values (?, 'SUBWAY', ?, ?, ?)",
                user, status, NOW, NOW);
        return jdbc.queryForObject("select max(id) from alarms", Long.class);
    }

    private long insertAlarmEvent(long user, long alarm, LocalDateTime createdAt, String deliveryStatus, long device) {
        jdbc.update("""
                insert into notification_events (source_type, alarm_id, user_id, activation_generation, tracking_cycle_id,
                    event_type, observed_at, event_detected_at, created_at)
                values ('ALARM', ?, ?, 1, ?, 'ARRIVED', ?, ?, ?)
                """, alarm, user, UUID.randomUUID().toString(), createdAt, createdAt, createdAt);
        long event = jdbc.queryForObject("select max(id) from notification_events", Long.class);
        insertDelivery(event, device, deliveryStatus, createdAt);
        return event;
    }

    private long insertBoardingEvent(long user, long session, LocalDateTime createdAt, String deliveryStatus, long device) {
        jdbc.update("""
                insert into notification_events (source_type, boarding_session_id, boarding_event_type, user_id,
                    observed_at, event_detected_at, created_at, expires_at)
                values ('BOARDING_SESSION', ?, 'PREPARE_ALIGHT', ?, ?, ?, ?, ?)
                """, session, user, createdAt, createdAt, createdAt, createdAt.plusMinutes(5));
        long event = jdbc.queryForObject("select max(id) from notification_events", Long.class);
        insertDelivery(event, device, deliveryStatus, createdAt);
        return event;
    }

    private void insertDelivery(long event, long device, String status, LocalDateTime at) {
        boolean pending = status.equals("PENDING");
        boolean accepted = status.equals("ACCEPTED");
        jdbc.update("""
                insert into notification_deliveries (notification_event_id, device_id, recipient_ownership_generation,
                    status, attempt_count, next_attempt_at, expires_at, last_attempt_at,
                    last_attempt_registration_revision, last_provider_result, last_failure_code,
                    provider_accepted_at, created_at, updated_at)
                values (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event, device, status, pending ? 0 : 1, pending ? at : null, at.plusMinutes(5),
                pending ? null : at, pending ? null : 1, pending ? null : accepted ? "ACCEPTED" : "RETRYABLE",
                pending || accepted ? null : "UNAVAILABLE", accepted ? at : null, at, at);
    }

    private long insertSession(long user, String status, LocalDateTime endedAt) {
        String reason = switch (status) {
            case "COMPLETED" -> "ARRIVAL_OBSERVED";
            case "CANCELLED" -> "USER_CANCELLED";
            default -> null;
        };
        jdbc.update("""
                insert into boarding_sessions (user_id, client_request_id, request_fingerprint, provider,
                    external_route_id, vehicle_tracking_id, vehicle_number, route_number, target_name,
                    traversal_snapshot, traversal_fingerprint, status, state_reason, revision,
                    checkpoint_stop_external_id, checkpoint_stop_order, checkpoint_index, checkpoint_evidence,
                    checkpoint_observed_at, created_at, updated_at, expires_at, ended_at)
                values (?, ?, ?, 'SEOUL_BUS', 'route', 'vehicle', '1234', '7000', '목적지', '{}', ?, ?, ?, 1,
                    's1', 1, 0, 'ID_ORDER', ?, ?, ?, ?, ?)
                """, user, UUID.randomUUID().toString(), "a".repeat(64), "b".repeat(64), status, reason,
                NOW, NOW, NOW, NOW.plusHours(4), endedAt);
        return jdbc.queryForObject("select max(id) from boarding_sessions", Long.class);
    }
}
