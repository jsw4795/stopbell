package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = {"transit.monitoring.enabled=false", "notification.dispatch.enabled=false", "notification.fcm.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class TransitMetadataPreflightIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { SharedMySqlExtension.database(registry, "preflight"); }
    @Autowired JdbcTemplate jdbc;
    @Autowired TransitMetadataPreflightService service;

    @Test void emptyDatabaseWarnsAndDoesNotWrite() {
        var result = service.inspect(TransitProvider.SEOUL_BUS);
        assertThat(result.status()).isEqualTo(TransitMetadataPreflightService.Status.WARNING);
        assertThat(result.normalized().get("routes")).isEqualTo(0L);
        assertThat(result.archives()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from transit_metadata_collection_runs", Long.class)).isZero();
    }

    @Test void providerCountsRepeatedVisitsOrphansAndGbisSeparately() {
        jdbc.update("insert into bus_routes(provider,external_route_id,route_number,city_code) values ('TAGO','r','1','1'),('SEOUL_BUS','s','2',null)");
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','a','A'),('TAGO','b','B'),('SEOUL_BUS','s','S')");
        jdbc.update("insert into bus_route_stop_occurrences(route_id,stop_id,stop_order) select r.id,s.id,n.n from bus_routes r join bus_stops s on s.external_stop_id='a' cross join (select 1 n union all select 2) n where r.external_route_id='r'");
        jdbc.update("insert into transit_source_metadata(source_system,data_kind,source_key,raw_payload,collected_at,updated_at) values ('GBIS','ROUTE','g','{}',utc_timestamp(),utc_timestamp()),('TAGO','ROUTE','t','{}',utc_timestamp(),utc_timestamp()),('SEOUL_TDATA','ROUTE','s','{}',utc_timestamp(),utc_timestamp())");
        var tables = java.util.List.of("bus_routes", "bus_stops", "bus_route_stop_occurrences", "transit_source_metadata", "bus_metadata_sync_states", "transit_metadata_collection_runs", "transit_metadata_archives", "transit_metadata_diff_analyses", "transit_metadata_unsafe_change_details", "transit_metadata_change_approvals");
        var before = tables.stream().map(table -> jdbc.queryForList("select * from " + table)).toList();
        var tago = service.inspect(TransitProvider.TAGO);
        assertThat(tago.normalized()).containsEntry("routes", 1L).containsEntry("stops", 2L).containsEntry("occurrences", 2L).containsEntry("orphan_stops", 1L).containsEntry("repeated_route_stop_pairs", 1L);
        assertThat(tago.sources()).extracting(m -> m.get("source_system")).containsOnly("TAGO", "GBIS");
        var seoul = service.inspect(TransitProvider.SEOUL_BUS);
        assertThat(seoul.normalized()).containsEntry("routes", 1L).containsEntry("stops", 1L).containsEntry("occurrences", 0L);
        assertThat(seoul.sources()).extracting(m -> m.get("source_system")).containsOnly("SEOUL_TDATA");
        assertThat(tables.stream().map(table -> jdbc.queryForList("select * from " + table)).toList()).isEqualTo(before);
    }

    @Test void reportsHistoryAndMissingArchive() {
        jdbc.update("insert into transit_metadata_collection_runs(provider,status,started_at,finished_at,created_at,updated_at) values ('TAGO','COMPLETED','2026-01-01','2026-01-01','2026-01-01','2026-01-01')");
        jdbc.update("insert into transit_metadata_collection_runs(provider,status,started_at,finished_at,failure_stage,failure_code,failure_message,created_at,updated_at) values ('TAGO','FAILED','2026-01-02','2026-01-02','SYNC','UNSAFE_CHANGE','x','2026-01-02','2026-01-02')");
        jdbc.update("insert into transit_metadata_collection_runs(provider,status,started_at,finished_at,created_at,updated_at) values ('TAGO','INTERRUPTED','2026-01-03','2026-01-03','2026-01-03','2026-01-03')");
        jdbc.update("insert into bus_metadata_sync_states(provider,last_complete_sync_at) values ('TAGO','2026-01-01')");
        var before = jdbc.queryForList("select * from transit_metadata_collection_runs order by id");
        var result = service.inspect(TransitProvider.TAGO);
        assertThat(result.runs()).extracting(m -> m.get("status")).containsExactlyInAnyOrder("COMPLETED", "FAILED", "INTERRUPTED");
        assertThat(result.syncState()).hasSize(1);
        assertThat(result.warnings()).anyMatch(w -> w.contains("아카이브"));
        assertThat(jdbc.queryForList("select * from transit_metadata_collection_runs order by id")).isEqualTo(before);
    }
    @Test void reportsUncertainApprovalAndMissingArchiveWithoutChangingThem() {
        jdbc.update("insert into transit_metadata_collection_runs(provider,status,started_at,finished_at,failure_stage,failure_code,failure_message,created_at,updated_at) values ('TAGO','FAILED','2026-01-01','2026-01-01','VALIDATION','UNSAFE_CHANGE','x','2026-01-01','2026-01-01')");
        long source = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs", Long.class);
        jdbc.update("insert into transit_metadata_collection_runs(provider,status,started_at,finished_at,created_at,updated_at) values ('TAGO','COMPLETED','2026-01-02','2026-01-02','2026-01-02','2026-01-02')");
        long apply = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs", Long.class);
        jdbc.update("insert into transit_metadata_change_approvals(source_run_id,provider,result_fingerprint,review_fingerprint,approved_at,expires_at,approval_note,status,apply_run_id,claimed_at,finished_at) values (?,'TAGO',repeat('a',64),repeat('b',64),'2026-01-01','2026-01-03','test','UNCERTAIN',?,'2026-01-02','2026-01-02')", source, apply);
        jdbc.update("insert into transit_metadata_archives(collection_run_id,source_system,logical_key_hash,file_name,sha256,size_bytes,stored_at) values (?,'TAGO',repeat('a',64),repeat('b',64),repeat('c',64),10,'2026-01-02')", apply);
        var before = jdbc.queryForList("select * from transit_metadata_change_approvals");
        var result = service.inspect(TransitProvider.TAGO);
        assertThat(result.approvals()).extracting(m -> m.get("status")).containsExactly("UNCERTAIN");
        assertThat(result.warnings()).anyMatch(w -> w.contains("UNCERTAIN"));
        assertThat(result.archives()).singleElement().satisfies(a -> assertThat(a.available()).isFalse());
        assertThat(jdbc.queryForList("select * from transit_metadata_change_approvals")).isEqualTo(before);
    }

    @Test void accessibleArchiveAllowsReadyWithoutClaimingFreshness(@TempDir Path directory) throws Exception {
        jdbc.update("insert into bus_routes(provider,external_route_id,route_number,city_code) values ('TAGO','r','1','1')");
        jdbc.update("insert into transit_metadata_collection_runs(provider,status,started_at,finished_at,created_at,updated_at) values ('TAGO','COMPLETED','2026-01-01','2026-01-01','2026-01-01','2026-01-01')");
        long run = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs", Long.class);
        String file = "b".repeat(64);
        Files.writeString(directory.resolve(file), "archive");
        jdbc.update("insert into transit_metadata_archives(collection_run_id,source_system,logical_key_hash,file_name,sha256,size_bytes,stored_at) values (?,'TAGO',repeat('a',64),?,repeat('c',64),7,'2026-01-01')", run, file);
        var result = new TransitMetadataPreflightService(jdbc, directory.toString()).inspect(TransitProvider.TAGO);
        assertThat(result.status()).isEqualTo(TransitMetadataPreflightService.Status.READY);
        assertThat(result.archives()).singleElement().satisfies(a -> assertThat(a.available()).isTrue());
        assertThat(result.warnings()).anyMatch(w -> w.contains("일치를 보장하지"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> result.normalized().put("routes", 999)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void unfinishedRunBlocksUntilInvestigated() {
        jdbc.update("insert into transit_metadata_collection_runs(provider,status,started_at,created_at,updated_at) values ('TAGO','SYNCING','2026-01-01','2026-01-01','2026-01-01')");
        assertThat(service.inspect(TransitProvider.TAGO).status()).isEqualTo(TransitMetadataPreflightService.Status.BLOCKED);
    }

}
