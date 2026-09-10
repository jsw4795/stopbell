package com.stopbell.alarm.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
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
    @DisplayName("V6과 V7 Migration은 기존 상태를 보존하고 Bus Target CHECK를 강제한다")
    void migrate_active_boolean_and_enforce_bus_target_constraints() {
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
                .target(MigrationVersion.fromVersion("6"))
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

        Long firstBusAlarmId = jdbcTemplate.queryForObject(
                "select id from alarms where transit_type = 'BUS' order by id limit 1",
                Long.class
        );
        assertThatCode(() -> insertBusAlarmTarget(
                jdbcTemplate,
                firstBusAlarmId,
                null,
                null,
                false,
                null,
                null,
                false,
                null,
                null
        )).doesNotThrowAnyException();

        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject("select count(*) from bus_alarm_targets", Integer.class)).isEqualTo(1);

        jdbcTemplate.update(
                "insert into alarms (user_id, transit_type, status, created_at, updated_at) values (?, ?, ?, ?, ?)",
                userId,
                "BUS",
                "INACTIVE",
                now,
                now
        );
        Long secondBusAlarmId = jdbcTemplate.queryForObject(
                "select id from alarms where user_id = ? and transit_type = 'BUS' order by id desc limit 1",
                Long.class,
                userId
        );

        assertThatThrownBy(() -> insertBusAlarmTarget(
                jdbcTemplate,
                secondBusAlarmId,
                null,
                new BigDecimal("127.1234567"),
                false,
                null,
                null,
                false,
                null,
                null
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_bus_alarm_targets_target_coordinates");
        assertThatThrownBy(() -> insertBusAlarmTarget(
                jdbcTemplate,
                secondBusAlarmId,
                new BigDecimal("37.1234567"),
                new BigDecimal("127.1234567"),
                true,
                "predecessor-stop",
                null,
                false,
                null,
                null
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_bus_alarm_targets_predecessor");
        assertThatThrownBy(() -> insertBusAlarmTarget(
                jdbcTemplate,
                secondBusAlarmId,
                new BigDecimal("37.1234567"),
                new BigDecimal("127.1234567"),
                false,
                null,
                null,
                true,
                "successor-stop",
                null
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_bus_alarm_targets_successor");

        assertThatCode(() -> insertBusAlarmTarget(
                jdbcTemplate,
                secondBusAlarmId,
                new BigDecimal("37.1234567"),
                new BigDecimal("127.1234567"),
                true,
                "predecessor-stop",
                9,
                true,
                "successor-stop",
                11
        )).doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject("select count(*) from bus_alarm_targets", Integer.class)).isEqualTo(2);
    }

    private void insertBusAlarmTarget(
            JdbcTemplate jdbcTemplate,
            Long alarmId,
            BigDecimal latitude,
            BigDecimal longitude,
            boolean notifyOneStopBefore,
            String predecessorExternalStopId,
            Integer predecessorStopOrder,
            boolean notifyOneStopAfter,
            String successorExternalStopId,
            Integer successorStopOrder
    ) {
        jdbcTemplate.update(
                "insert into bus_alarm_targets ("
                        + "alarm_id, provider, external_route_id, external_stop_id, target_stop_order, route_number, "
                        + "stop_name, target_stop_latitude, target_stop_longitude, city_code, notify_one_stop_before, "
                        + "predecessor_external_stop_id, predecessor_stop_order, notify_one_stop_after, "
                        + "successor_external_stop_id, successor_stop_order"
                        + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                alarmId,
                "TAGO",
                "route-1",
                "target-stop",
                10,
                "1000",
                "Target Stop",
                latitude,
                longitude,
                "41110",
                notifyOneStopBefore,
                predecessorExternalStopId,
                predecessorStopOrder,
                notifyOneStopAfter,
                successorExternalStopId,
                successorStopOrder
        );
    }
}
