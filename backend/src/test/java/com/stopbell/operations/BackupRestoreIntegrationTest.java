package com.stopbell.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.stopbell.support.SharedMySqlExtension;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@ExtendWith(SharedMySqlExtension.class)
@Testcontainers
class BackupRestoreIntegrationTest {
    private SharedMySqlExtension.Database source;
    @Container static final MySQLContainer destination = new MySQLContainer("mysql:8.4.11");

    @BeforeEach
    void connect_to_isolated_source(SharedMySqlExtension.Database database) {
        source = database;
    }

    @Test
    @DisplayName("격리 DB의 백업을 별도 DB에 복원하면 스키마와 알림 데이터가 보존된다")
    void restores_schema_and_notification_data() throws Exception {
        Flyway sourceFlyway = Flyway.configure().dataSource(source.getJdbcUrl(), source.getUsername(), source.getPassword()).load();
        sourceFlyway.migrate();
        JdbcTemplate original = new JdbcTemplate(new DriverManagerDataSource(source.getJdbcUrl(), source.getUsername(), source.getPassword()));
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
        original.update("insert into users (auth_provider, provider_user_id, created_at, updated_at) values ('GOOGLE', 'synthetic', ?, ?)", now, now);
        original.update("insert into users (auth_provider, provider_user_id, created_at, updated_at) values ('APPLE', 'synthetic-apple', ?, ?)", now, now);
        original.update("insert into apple_credentials (user_id, encrypted_refresh_token) values (2, 'v1.fixture.synthetic-ciphertext')");
        original.update("insert into alarms (user_id, transit_type, status, created_at, updated_at) values (1, 'BUS', 'ACTIVE', ?, ?)", now, now);
        original.update("insert into devices (user_id, installation_id, installation_credential_hash, platform, current_push_target_id, registration_revision, enabled, last_registered_at, created_at, updated_at) values (1, '12345678-1234-4123-8123-123456789abc', ?, 'IOS', 'synthetic', 0, true, ?, ?, ?)", "a".repeat(64), now, now, now);
        original.update("insert into notification_events (source_type, alarm_id, user_id, activation_generation, tracking_cycle_id, event_type, observed_at, event_detected_at, created_at) values ('ALARM', 1, 1, 0, '12345678-1234-4123-8123-123456789abc', 'ONE_STOP_BEFORE', ?, ?, ?)", now, now, now);
        original.update("insert into notification_deliveries (notification_event_id, device_id, recipient_ownership_generation, status, next_attempt_at, expires_at, created_at, updated_at) values (1, 1, 0, 'PENDING', ?, ?, ?, ?)", now, now.plusMinutes(1), now, now);

        original.update("insert into account_deletions (user_id,state,requested_at,next_attempt_at) values (2,'PENDING',?,?)", now, now.plusMinutes(1));

        var dump = source.dump();
        assertThat(dump.getExitCode()).isZero();
        destination.copyFileToContainer(org.testcontainers.images.builder.Transferable.of(dump.getStdout()), "/tmp/stopbell-test.sql");
        var restore = destination.execInContainer("sh", "-c", "MYSQL_PWD=\"$MYSQL_PASSWORD\" mysql -u\"$MYSQL_USER\" \"$MYSQL_DATABASE\" < /tmp/stopbell-test.sql");
        assertThat(restore.getExitCode()).isZero();
        Flyway restoredFlyway = flyway(destination);
        restoredFlyway.validate();
        assertThat(restoredFlyway.info().pending()).isEmpty();
        assertThat(restoredFlyway.info().current().getVersion().getVersion()).isEqualTo("33");
        JdbcTemplate restored = jdbc(destination);
        for (String table : List.of("users", "apple_credentials", "account_deletions", "alarms", "devices", "notification_events", "notification_deliveries", "flyway_schema_history")) {
            List<Map<String, Object>> expected = original.queryForList("select * from " + table + " order by 1");
            assertThat(restored.queryForList("select * from " + table + " order by 1")).as(table).isEqualTo(expected);
        }
    }

    private static Flyway flyway(MySQLContainer container) {
        return Flyway.configure().dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword()).load();
    }

    private static JdbcTemplate jdbc(MySQLContainer container) {
        return new JdbcTemplate(new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword()));
    }
}
