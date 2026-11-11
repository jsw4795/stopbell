package com.stopbell.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.stopbell.support.SharedMySqlExtension;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("db")
@Tag("migration")
@ExtendWith(SharedMySqlExtension.class)
class BoardingTimelineMigrationTest {
    @Test
    void upgrade_preserves_existing_session_and_accepts_nullable_boarding_identity(SharedMySqlExtension.Database db) {
        // flyway-historical-target
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .target(MigrationVersion.fromVersion("39")).load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
        jdbc.update("insert into users (auth_provider, provider_user_id, created_at, updated_at) values ('GOOGLE','legacy',now(),now())");
        jdbc.update("""
                insert into boarding_sessions (user_id, client_request_id, request_fingerprint, provider, external_route_id,
                    vehicle_tracking_id, vehicle_number, route_number, target_name, traversal_snapshot, traversal_fingerprint,
                    status, revision, checkpoint_stop_external_id, checkpoint_stop_order, checkpoint_index, checkpoint_evidence,
                    checkpoint_observed_at, created_at, updated_at, expires_at)
                values ((select id from users where provider_user_id='legacy'), '11111111-1111-4111-8111-111111111111',
                    ?, 'SEOUL_BUS', 'route', 'vehicle', 'number', 'route', 'target', '{}', ?, 'TRACKING', 0,
                    'stop', 1, 0, 'ID_ORDER', now(), now(), now(), date_add(now(), interval 4 hour))
                """, "a".repeat(64), "b".repeat(64));
        var before = jdbc.queryForMap("select * from boarding_sessions");
        var flyway = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load();
        com.stopbell.support.FlywayTestAssertions.migrateToLatest(flyway);
        var after = jdbc.queryForMap("select * from boarding_sessions");
        assertThat(after.remove("boarding_occurrence_id")).isNull();
        assertThat(after).isEqualTo(before);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
