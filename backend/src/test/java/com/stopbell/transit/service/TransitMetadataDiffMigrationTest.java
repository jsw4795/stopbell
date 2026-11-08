package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.*;
import com.stopbell.support.SharedMySqlExtension;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;

@ExtendWith(SharedMySqlExtension.class)
class TransitMetadataDiffMigrationTest {
    @Test void migratesV35AndEnforcesRunAndUniqueness(SharedMySqlExtension.Database db) {
        var ds = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        assertThat(Flyway.configure().dataSource(ds).target("35").load().migrate().migrationsExecuted).isEqualTo(35);
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into transit_metadata_collection_runs (id,provider,status,started_at,created_at,updated_at) values (1,'TAGO','COLLECTING',now(6),now(6),now(6))");
        assertThat(Flyway.configure().dataSource(ds).target("36").load().migrate().migrationsExecuted).isOne();
        String insert = "insert into transit_metadata_diff_analyses (collection_run_id,provider,baseline_type,analyzed_at,result_fingerprint,summary) values (?, 'TAGO', 'CURRENT_DB_BEFORE_APPLY', now(6), ?, '{}')";
        assertThatThrownBy(() -> jdbc.update(insert, 99, "a".repeat(64))).isInstanceOf(DataAccessException.class);
        jdbc.update(insert, 1, "a".repeat(64));
        assertThatThrownBy(() -> jdbc.update(insert, 1, "b".repeat(64))).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from transit_metadata_collection_runs", Integer.class)).isOne();
        assertThat(Flyway.configure().dataSource(ds).target("37").load().migrate().migrationsExecuted).isOne();
        String detail = "insert into transit_metadata_unsafe_change_details (collection_run_id,sequence_number,reason,route_id) values (?, 0, 'ROUTE_REMOVAL', 'route')";
        assertThatThrownBy(() -> jdbc.update(detail, 99)).isInstanceOf(DataAccessException.class);
        jdbc.update(detail, 1);
        assertThatThrownBy(() -> jdbc.update(detail, 1)).isInstanceOf(DataAccessException.class);
        assertThat(Flyway.configure().dataSource(ds).load().migrate().migrationsExecuted).isOne();
        String approval = "insert into transit_metadata_change_approvals (source_run_id,provider,result_fingerprint,review_fingerprint,approved_at,expires_at,approval_note,status) values (?, 'TAGO', ?, ?, now(6), now(6) + interval 1 hour, 'reviewed', ?)";
        assertThatThrownBy(() -> jdbc.update(approval, 99, "a".repeat(64), "b".repeat(64), "APPROVED")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(approval, 1, "a".repeat(64), "b".repeat(64), "INVALID")).isInstanceOf(DataAccessException.class);
        jdbc.update(approval, 1, "a".repeat(64), "b".repeat(64), "APPROVED");
        assertThatThrownBy(() -> jdbc.update(approval, 1, "a".repeat(64), "b".repeat(64), "APPROVED")).isInstanceOf(DataAccessException.class);
        assertThat(Flyway.configure().dataSource(ds).load().migrate().migrationsExecuted).isZero();
    }
}
