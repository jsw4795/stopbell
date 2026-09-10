package com.stopbell.alarm.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class AlarmMigrationIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Test
    @DisplayName("기존 active 값은 V6 Migration에서 ACTIVE와 INACTIVE 상태로 보존된다")
    void migrate_active_boolean_to_alarm_status() {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .target(MigrationVersion.fromVersion("5"))
                .load()
                .migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(),
                mysql.getUsername(),
                mysql.getPassword()
        ));
        LocalDateTime now = LocalDateTime.of(2026, 9, 10, 12, 0);
        jdbcTemplate.update(
                "insert into users (auth_provider, provider_user_id, created_at, updated_at) values (?, ?, ?, ?)",
                "GOOGLE",
                "migration-user",
                now,
                now
        );
        Long userId = jdbcTemplate.queryForObject(
                "select id from users where provider_user_id = 'migration-user'",
                Long.class
        );
        jdbcTemplate.update(
                "insert into alarms (user_id, transit_type, active, created_at, updated_at) values (?, ?, ?, ?, ?)",
                userId,
                "BUS",
                true,
                now,
                now
        );
        jdbcTemplate.update(
                "insert into alarms (user_id, transit_type, active, created_at, updated_at) values (?, ?, ?, ?, ?)",
                userId,
                "SUBWAY",
                false,
                now,
                now
        );

        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .load()
                .migrate();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select transit_type, status from alarms order by id"
        );
        Integer activeColumnCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema = database() and table_name = 'alarms' and column_name = 'active'",
                Integer.class
        );
        Integer targetCount = jdbcTemplate.queryForObject("select count(*) from bus_alarm_targets", Integer.class);

        assertThat(rows).containsExactly(
                Map.of("transit_type", "BUS", "status", "ACTIVE"),
                Map.of("transit_type", "SUBWAY", "status", "INACTIVE")
        );
        assertThat(activeColumnCount).isZero();
        assertThat(targetCount).isZero();
    }
}
