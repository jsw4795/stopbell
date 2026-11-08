package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.*;

import com.stopbell.support.SharedMySqlExtension;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@ExtendWith(SharedMySqlExtension.class)
class TransitMetadataCollectionMigrationTest {
    @Test @DisplayName("V34는 기존 데이터를 보존하고 이력 제약을 적용한다")
    void migrates_and_preserves_existing_data(SharedMySqlExtension.Database database) {
        var ds = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        Flyway.configure().dataSource(ds).target("33").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO bus_routes (id,provider,external_route_id,route_number) VALUES (101,'SEOUL_BUS','r','1')");
        jdbc.update("INSERT INTO bus_stops (id,provider,external_stop_id,stop_name) VALUES (102,'SEOUL_BUS','s','stop')");
        jdbc.update("INSERT INTO bus_route_stop_occurrences (id,route_id,stop_id,stop_order) VALUES (103,101,102,1)");
        jdbc.update("INSERT INTO bus_metadata_sync_states (provider,last_complete_sync_at) VALUES ('SEOUL_BUS',NOW(6))");
        jdbc.update("INSERT INTO transit_source_metadata (source_system,data_kind,source_key,raw_payload,collected_at,updated_at) VALUES ('SEOUL_TDATA','ROUTE','r','{}',NOW(6),NOW(6))");
        assertThat(Flyway.configure().dataSource(ds).target("34").load().migrate().migrationsExecuted).isOne();
        for (String table : new String[] {"bus_routes", "bus_stops", "bus_route_stop_occurrences", "bus_metadata_sync_states", "transit_source_metadata"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isOne();
        }
        String insert = "INSERT INTO transit_metadata_collection_runs (provider,status,started_at,created_at,updated_at) VALUES (?, ?, NOW(6),NOW(6),NOW(6))";
        jdbc.update(insert, "TAGO", "COLLECTING");
        assertThatThrownBy(() -> jdbc.update(insert, "GBIS", "COLLECTING")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "TAGO", "INVALID")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "TAGO", "COMPLETED")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(insert, "TAGO", "FAILED")).isInstanceOf(DataAccessException.class);
        assertThat(Flyway.configure().dataSource(ds).load().migrate().migrationsExecuted).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_metadata_collection_runs", Integer.class)).isOne();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO transit_metadata_archives (collection_run_id, source_system, logical_key_hash, file_name, sha256, size_bytes, stored_at) VALUES (99999, 'TAGO', ?, ?, ?, 1, NOW(6))", "a".repeat(64), "b".repeat(64), "c".repeat(64))).isInstanceOf(DataAccessException.class);
    }
}
