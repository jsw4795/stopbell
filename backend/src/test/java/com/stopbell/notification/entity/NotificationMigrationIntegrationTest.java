package com.stopbell.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class NotificationMigrationIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Test
    @DisplayName("V14는 기존 FOLLOW_UP이 있으면 DDL 전에 중단하고 비어 있지 않은 History는 삭제 후에도 보존한다")
    void guard_follow_up_and_preserve_legacy_history() {
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(dataSource).target("13").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into users (auth_provider, provider_user_id, created_at, updated_at) values ('GOOGLE', 'legacy-user', '2026-10-09', '2026-10-09')");
        Long userId = jdbc.queryForObject("select id from users", Long.class);
        jdbc.update("insert into alarms (user_id, transit_type, status, follow_up_vehicle_tracking_id, follow_up_started_at, follow_up_expires_at, created_at, updated_at) values (?, 'SUBWAY', 'FOLLOW_UP', 'original-vehicle', '2026-10-09', '2026-10-09 00:05:00', '2026-10-09', '2026-10-09')", userId);
        Long alarmId = jdbc.queryForObject("select id from alarms", Long.class);
        jdbc.update("insert into notification_history (alarm_id, status, failure_reason, created_at) values (?, 'FAILURE', 'original-reason', '2026-10-09')", alarmId);
        var originalHistory = jdbc.queryForList("select * from notification_history");
        var originalAlarm = jdbc.queryForList("select * from alarms");
        ResourceDatabasePopulator migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V14__create_notification_outbox.sql"));
        assertThatThrownBy(() -> migration.execute(dataSource)).hasStackTraceContaining("ck_task707_no_legacy_follow_up");
        assertThat(jdbc.queryForList("select * from alarms")).isEqualTo(originalAlarm);
        assertThat(jdbc.queryForList("select * from notification_history")).isEqualTo(originalHistory);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='alarms' and column_name='follow_up_tracking_cycle_id'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name='notification_events'", Integer.class)).isZero();

        // Simulate normal completion of this test fixture's old follow-up; no invented UUID.
        jdbc.update("update alarms set status='INACTIVE', follow_up_vehicle_tracking_id=null, follow_up_started_at=null, follow_up_expires_at=null where id=?", alarmId);
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(jdbc.queryForList("select * from notification_history")).isEqualTo(originalHistory);
        jdbc.update("delete from alarms where id=?", alarmId);
        assertThat(jdbc.queryForList("select * from notification_history")).isEqualTo(originalHistory);
        assertThat(jdbc.queryForObject("select version from flyway_schema_history order by installed_rank desc limit 1", String.class)).isEqualTo("14");
    }
}
