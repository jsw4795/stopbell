package com.stopbell.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class UserIdentityMigrationIntegrationTest {

    // Each invocation owns a new container, including failed Flyway history and DDL.
    @Container
    final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    private JdbcTemplate jdbc;

    @BeforeEach
    void connect_to_isolated_database() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("8.4.11");
        assertThat(jdbc.queryForObject("select @@session.sql_mode", String.class))
                .contains("STRICT_TRANS_TABLES");
    }

    @Test
    @DisplayName("User가 없는 V3는 V4 Identity 제약을 생성하고 최신 Migration까지 적용된다")
    void upgrade_empty_v3() {
        flyway("3", true).migrate();
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
        assertThat(flyway("4", true).migrate().migrationsExecuted).isEqualTo(1);
        assertIdentitySchema();
        assertThat(flyway("latest", true).migrate().migrationsExecuted).isPositive();
        assertLatestAndNoOp();
    }

    @Test
    @DisplayName("Fresh DB의 전체 Migration은 Identity NOT NULL과 Provider별 복합 UNIQUE를 유지한다")
    void migrate_fresh_database() {
        assertThat(flyway("latest", true).migrate().migrationsExecuted).isEqualTo(20);
        assertIdentitySchema();
        insertIdentity("GOOGLE", "same-sub");
        insertIdentity("APPLE", "same-sub");
        assertThat(jdbc.queryForObject("select count(distinct id) from users", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> insertIdentity("GOOGLE", "same-sub"))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("uk_users_auth_provider_provider_user_id");
        assertThatThrownBy(() -> insertIdentity(null, "other-sub")).hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertIdentity("GOOGLE", null)).hasRootCauseInstanceOf(SQLException.class);
        assertLatestAndNoOp();
    }

    @ParameterizedTest(name = "users={0}, strict={1}")
    @CsvSource({"1,true", "1,false", "2,true", "2,false", "10,true", "10,false"})
    @DisplayName("기존 User 한 명은 빈 Identity로 통과하고 여러 명은 UNIQUE 실패하며 원본 관계는 보존된다")
    void observe_legacy_upgrade(int userCount, boolean strict) {
        flyway("3", strict).migrate();
        for (int index = 1; index <= userCount; index++) {
            jdbc.update("insert into users (id, created_at, updated_at) values (?, '2026-09-01 12:34:56.123456', '2026-09-02 13:45:57.654321')", index * 10L);
            jdbc.update("insert into alarms (id, user_id, transit_type, active, created_at, updated_at) values (?, ?, 'SUBWAY', false, '2026-09-01 12:34:56.123456', '2026-09-02 13:45:57.654321')", index * 100L, index * 10L);
            jdbc.update("insert into notification_history (id, alarm_id, status, failure_reason, created_at) values (?, ?, 'FAILURE', 'legacy-fixture', '2026-09-02 13:45:57.654321')", index * 1000L, index * 100L);
        }
        var users = jdbc.queryForList("select id, created_at, updated_at from users order by id");
        var alarms = jdbc.queryForList("select * from alarms order by id");
        var notifications = jdbc.queryForList("select * from notification_history order by id");
        var schema = jdbc.queryForList("show create table users");
        var history = jdbc.queryForList("select * from flyway_schema_history order by installed_rank");
        var foreignKeys = foreignKeys();
        Flyway upgrade = flyway("4", strict);

        if (userCount == 1) {
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertIdentitySchema();
            assertThat(jdbc.queryForList("select auth_provider, provider_user_id from users"))
                    .containsExactly(Map.of("auth_provider", "", "provider_user_id", ""));
            assertThat(Arrays.stream(AuthProvider.values()).map(Enum::name)).doesNotContain("");
            assertThat(jdbc.queryForObject("select success from flyway_schema_history where version='4'", Boolean.class)).isTrue();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
        } else {
            Throwable failure = catchThrowable(upgrade::migrate);
            assertThat(failure).isInstanceOf(FlywayException.class);
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(SQLException.class);
            SQLException sql = (SQLException) cause;
            assertThat(sql.getSQLState()).isEqualTo("23000");
            assertThat(sql.getErrorCode()).isEqualTo(1062);
            assertThat(sql.getMessage()).contains("uk_users_auth_provider_provider_user_id");
            // Observe atomicity of this particular single ALTER, not a general DDL rollback promise.
            assertThat(jdbc.queryForList("show create table users")).isEqualTo(schema);
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='users' and column_name in ('auth_provider', 'provider_user_id')", Integer.class)).isZero();
            assertThat(jdbc.queryForList("select * from flyway_schema_history where version <> '4' order by installed_rank")).isEqualTo(history);
            assertThat(jdbc.queryForList("select version, success from flyway_schema_history where version='4'"))
                    .containsExactly(Map.of("version", "4", "success", false));
            var failedHistory = jdbc.queryForList("select * from flyway_schema_history order by installed_rank");
            // A new Flyway instance represents another startup; no repair or schema mutation.
            assertThatThrownBy(() -> flyway("latest", strict).migrate())
                    .isInstanceOf(FlywayException.class).hasMessageContaining("failed migration");
            assertThat(jdbc.queryForList("select * from flyway_schema_history order by installed_rank")).isEqualTo(failedHistory);
            assertThat(jdbc.queryForList("show create table users")).isEqualTo(schema);
            assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name='refresh_tokens'", Integer.class)).isZero();
            System.out.printf("AUD-11 users=%d strict=%s SQLState=%s code=%d message=%s%n",
                    userCount, strict, sql.getSQLState(), sql.getErrorCode(), sql.getMessage());
        }

        assertThat(jdbc.queryForList("select id, created_at, updated_at from users order by id")).isEqualTo(users);
        assertThat(jdbc.queryForList("select * from alarms order by id")).isEqualTo(alarms);
        assertThat(jdbc.queryForList("select * from notification_history order by id")).isEqualTo(notifications);
        assertThat(foreignKeys()).isEqualTo(foreignKeys);
        assertThat(jdbc.queryForObject("select count(*) from users u join alarms a on a.user_id=u.id join notification_history n on n.alarm_id=a.id", Integer.class)).isEqualTo(userCount);
    }

    private Flyway flyway(String target, boolean strict) {
        return Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .initSql(strict ? "SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'" : "SET SESSION sql_mode=''")
                .target(target).load();
    }

    private void assertIdentitySchema() {
        assertThat(jdbc.queryForList("select column_name, is_nullable from information_schema.columns where table_schema=database() and table_name='users' and column_name in ('auth_provider', 'provider_user_id') order by ordinal_position"))
                .containsExactly(Map.of("COLUMN_NAME", "auth_provider", "IS_NULLABLE", "NO"),
                        Map.of("COLUMN_NAME", "provider_user_id", "IS_NULLABLE", "NO"));
        assertThat(jdbc.queryForList("select column_name from information_schema.statistics where table_schema=database() and table_name='users' and index_name='uk_users_auth_provider_provider_user_id' and non_unique=0 order by seq_in_index", String.class))
                .containsExactly("auth_provider", "provider_user_id");
    }

    private List<Map<String, Object>> foreignKeys() {
        return jdbc.queryForList("select table_name, column_name, referenced_table_name, referenced_column_name from information_schema.key_column_usage where table_schema=database() and referenced_table_name is not null order by table_name, column_name");
    }

    private void insertIdentity(String provider, String sub) {
        jdbc.update("insert into users (auth_provider, provider_user_id, created_at, updated_at) values (?, ?, '2026-09-01', '2026-09-01')", provider, sub);
    }

    private void assertLatestAndNoOp() {
        Flyway latest = flyway("latest", true);
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("20");
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        var history = jdbc.queryForList("select * from flyway_schema_history order by installed_rank");
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("select * from flyway_schema_history order by installed_rank")).isEqualTo(history);
    }
}
