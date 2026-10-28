package com.stopbell.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.stopbell.boarding.BoardingSessionFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class NotificationSourceMigrationIntegrationTest {
    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Test
    @DisplayName("V16과 V17은 기존 Event·Delivery·Session을 보존하고 사전 알림 기본값과 UNIQUE를 유지한다")
    void preserve_existing_events_deliveries_and_sessions() {
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(dataSource).target("14").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var now = BoardingSessionFixtures.UTC_NOW;
        jdbc.update("""
                insert into users (auth_provider, provider_user_id, created_at, updated_at)
                values ('GOOGLE', 'source-migration-owner', ?, ?)
                """, now, now);
        Long userId = jdbc.queryForObject("select id from users", Long.class);
        jdbc.update("""
                insert into alarms (user_id, transit_type, status, created_at, updated_at)
                values (?, 'SUBWAY', 'INACTIVE', ?, ?)
                """, userId, now, now);
        Long alarmId = jdbc.queryForObject("select id from alarms", Long.class);
        for (String type : List.of("ONE_STOP_BEFORE", "ARRIVED", "PASSED", "ONE_STOP_AFTER")) {
            jdbc.update("""
                    insert into notification_events (alarm_id, user_id, activation_generation, tracking_cycle_id,
                        event_type, observed_at, event_detected_at, created_at, current_stop_external_id,
                        current_stop_name, current_stop_order, latitude, longitude, provider_data_time, stops_past_target)
                    values (?, ?, 5, ?, ?, ?, ?, ?, 'stop', '최근 정류장', 30, 37.1234567, 127.1234567, ?, 2)
                    """, alarmId, userId, UUID.randomUUID().toString(), type, now, now, now, now.minusSeconds(1));
        }
        Long eventId = jdbc.queryForObject("select min(id) from notification_events", Long.class);
        for (String status : List.of("PENDING", "ACCEPTED", "FAILED", "EXPIRED")) {
            jdbc.update("""
                    insert into devices (user_id, installation_id, installation_credential_hash, platform,
                        current_push_target_id, registration_revision, ownership_generation, enabled,
                        last_registered_at, created_at, updated_at)
                    values (?, ?, ?, 'IOS', ?, 7, 3, true, ?, ?, ?)
                    """, userId, UUID.randomUUID().toString(), "a".repeat(64), "fixture-" + status, now, now, now);
            Long deviceId = jdbc.queryForObject("select id from devices where current_push_target_id=?", Long.class, "fixture-" + status);
            boolean accepted = status.equals("ACCEPTED");
            jdbc.update("""
                    insert into notification_deliveries (notification_event_id, device_id, recipient_ownership_generation,
                        status, attempt_count, next_attempt_at, expires_at, last_attempt_at,
                        last_attempt_registration_revision, last_provider_result, last_failure_code,
                        provider_accepted_at, created_at, updated_at)
                    values (?, ?, 3, ?, 1, ?, ?, ?, 7, ?, ?, ?, ?, ?)
                    """, eventId, deviceId, status, status.equals("PENDING") ? now : null, now.plusSeconds(30), now,
                    accepted ? "ACCEPTED" : "RETRYABLE", accepted ? null : "UNAVAILABLE",
                    accepted ? now : null, now, now);
        }
        var oldEvents = jdbc.queryForList("select * from notification_events order by id");
        var oldDeliveries = jdbc.queryForList("select * from notification_deliveries order by id");
        var oldDevices = jdbc.queryForList("select * from devices order by id");
        var oldAlarms = jdbc.queryForList("select * from alarms order by id");

        Flyway.configure().dataSource(dataSource).target("15").load().migrate();
        var start = BoardingSessionFixtures.verified(BoardingSessionFixtures.metadata());
        jdbc.update("""
                insert into boarding_sessions (user_id, client_request_id, request_fingerprint, provider,
                    external_route_id, vehicle_tracking_id, vehicle_number, route_number, target_name,
                    traversal_snapshot, traversal_fingerprint, status, revision,
                    checkpoint_stop_external_id, checkpoint_stop_order, checkpoint_index, checkpoint_evidence,
                    checkpoint_observed_at, created_at, updated_at, expires_at)
                values (?, ?, ?, 'SEOUL_BUS', 'route-id', 'vehicle-id', ?, '7000', '목적지', ?, ?, 'TRACKING', 2,
                    's1', 10, 0, 'ID_ORDER', ?, ?, ?, ?)
                """, userId, BoardingSessionFixtures.REQUEST_ID, "a".repeat(64), BoardingSessionFixtures.NUMBER,
                start.traversal().toJson(), start.traversal().fingerprint(), now, now, now, now.plusHours(4));
        var oldSessions = jdbc.queryForList("select * from boarding_sessions");

        var latest = Flyway.configure().dataSource(dataSource).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select version from flyway_schema_history order by installed_rank desc limit 1", String.class))
                .isEqualTo("17");
        var migratedEvents = jdbc.queryForList("select * from notification_events order by id");
        assertThat(migratedEvents).hasSameSizeAs(oldEvents);
        for (int i = 0; i < oldEvents.size(); i++) {
            assertThat(migratedEvents.get(i)).containsAllEntriesOf(oldEvents.get(i))
                    .containsEntry("source_type", "ALARM")
                    .containsEntry("boarding_session_id", null)
                    .containsEntry("boarding_event_type", null)
                    .containsEntry("expires_at", null);
        }
        assertThat(jdbc.queryForList("select * from notification_deliveries order by id")).isEqualTo(oldDeliveries);
        assertThat(jdbc.queryForList("select status from notification_deliveries order by id", String.class))
                .containsExactly("PENDING", "ACCEPTED", "FAILED", "EXPIRED");
        assertThat(jdbc.queryForList("select * from devices order by id")).isEqualTo(oldDevices);
        assertThat(jdbc.queryForList("select * from alarms order by id")).isEqualTo(oldAlarms);
        assertThat(jdbc.queryForList("select * from boarding_sessions")).singleElement()
                .satisfies(row -> assertThat(row).containsAllEntriesOf(oldSessions.getFirst()));
        assertThat(jdbc.queryForObject("select prepare_notification_enabled from boarding_sessions", Boolean.class)).isTrue();
        assertThatThrownBy(() -> jdbc.update("""
                insert into notification_events (source_type, alarm_id, user_id, activation_generation, tracking_cycle_id,
                    event_type, observed_at, event_detected_at, created_at)
                select source_type, alarm_id, user_id, activation_generation, tracking_cycle_id,
                    event_type, observed_at, event_detected_at, created_at from notification_events where id=?
                """, eventId)).isInstanceOf(DataAccessException.class).hasMessageContaining("uk_notification_events_logical_identity");
        assertThatThrownBy(() -> jdbc.update("""
                insert into notification_deliveries (notification_event_id, device_id, recipient_ownership_generation,
                    status, next_attempt_at, expires_at, created_at, updated_at)
                select notification_event_id, device_id, recipient_ownership_generation,
                    'PENDING', created_at, expires_at, created_at, updated_at from notification_deliveries limit 1
                """)).isInstanceOf(DataAccessException.class).hasMessageContaining("uk_notification_deliveries_event_device");
    }
}
