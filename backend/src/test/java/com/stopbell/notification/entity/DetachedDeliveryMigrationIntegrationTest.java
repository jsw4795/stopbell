package com.stopbell.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.stopbell.support.SharedMySqlExtension;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("db")
@Tag("migration")
@ExtendWith(SharedMySqlExtension.class)
class DetachedDeliveryMigrationIntegrationTest {
    private SharedMySqlExtension.Database mysql;

    @BeforeEach
    void connect_to_isolated_database(SharedMySqlExtension.Database database) {
        mysql = database;
    }

    @Test
    @DisplayName("V23에서 V24로 갱신하면 Device 참조는 nullable이 되며 기존 FK와 UNIQUE는 보존된다")
    void upgrade_keeps_constraints() {
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(dataSource).target("23").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(nullable(jdbc)).isEqualTo("NO");
        jdbc.update("insert into users (id, auth_provider, provider_user_id, created_at, updated_at) values (1, 'GOOGLE', 'synthetic', '2026-10-01', '2026-10-01')");
        jdbc.update("""
                insert into devices (id, user_id, installation_id, installation_credential_hash, platform,
                  current_push_target_id, registration_revision, enabled, last_registered_at, created_at, updated_at)
                values (1, 1, '00000000-0000-4000-8000-000000000001', ?, 'IOS', 'synthetic-fid', 0, true,
                  '2026-10-01', '2026-10-01', '2026-10-01')
                """, "a".repeat(64));
        jdbc.update("""
                insert into notification_events (id, source_type, alarm_id, user_id, activation_generation,
                  tracking_cycle_id, event_type, observed_at, event_detected_at, created_at)
                values (1, 'ALARM', 1, 1, 0, '00000000-0000-4000-8000-000000000001', 'ARRIVED',
                  '2026-10-01', '2026-10-01', '2026-10-01')
                """);
        jdbc.update("""
                insert into notification_deliveries (id, notification_event_id, device_id, recipient_ownership_generation,
                  status, attempt_count, next_attempt_at, expires_at, created_at, updated_at)
                values (1, 1, 1, 0, 'PENDING', 0, '2026-10-01', '2026-10-02', '2026-10-01', '2026-10-01')
                """);
        var before = jdbc.queryForList("select * from notification_deliveries");
        assertThat(Flyway.configure().dataSource(dataSource).target("24").load().migrate().migrationsExecuted).isOne();
        assertThat(jdbc.queryForList("select * from notification_deliveries")).isEqualTo(before);
        assertThat(nullable(jdbc)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.key_column_usage
                where table_schema=database() and table_name='notification_deliveries'
                  and constraint_name='fk_notification_deliveries_device_id'
                  and referenced_table_name='devices' and referenced_column_name='id'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.statistics
                where table_schema=database() and table_name='notification_deliveries'
                  and index_name='uk_notification_deliveries_event_device' and non_unique=0
                """, Integer.class)).isEqualTo(2);
    }

    private String nullable(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_schema=database() and table_name='notification_deliveries' and column_name='device_id'
                """, String.class);
    }
}
