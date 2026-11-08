package com.stopbell.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.metadata.GbisBulkMetadata;
import com.stopbell.transit.metadata.GbisCollectedSnapshot;
import com.stopbell.transit.metadata.GbisCollectedSnapshot.RawRow;
import com.stopbell.transit.metadata.TagoMetadataSource;
import com.stopbell.transit.service.BusMetadataSyncService;
import com.stopbell.transit.service.BusRouteMetadataSnapshot;
import com.stopbell.transit.service.BusStopOccurrenceMetadataSnapshot;
import com.stopbell.transit.service.CompleteBusMetadataSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = {"transit.monitoring.enabled=false", "notification.dispatch.enabled=false", "notification.fcm.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GbisRawMetadataSyncIntegrationTest {
    private static final Instant TIME = Instant.parse("2026-11-07T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private com.stopbell.transit.service.TransitMetadataDiffAnalysisService analysis;
    @Autowired BusMetadataSyncService sync;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.stopbell.transit.service.TransitMetadataCollectionHistoryService history;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { SharedMySqlExtension.database(registry, "gbis_raw_sync"); }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM transit_source_metadata");
        jdbc.update("DELETE FROM bus_route_stop_occurrences");
        jdbc.update("DELETE FROM bus_routes");
        jdbc.update("DELETE FROM bus_stops");
        jdbc.update("DELETE FROM bus_metadata_sync_states");
    }

    @Test
    @DisplayName("GBIS 전체 원본을 보존하고 완전 일치 경유만 연결하며 재처리와 연결 해제를 반영한다")
    void stores_matches_and_unlinks_on_mismatch() {
        var snapshot = snapshot(2, "GGB10", "20261001", "014");
        sync.syncTagoSnapshot(snapshot, TIME);
        var first = gbisRows();
        assertThat(first).hasSize(6);
        assertThat(count("source_system='GBIS' AND route_id IS NOT NULL")).isEqualTo(1);
        assertThat(count("source_system='GBIS' AND occurrence_id IS NOT NULL")).isEqualTo(2);
        assertThat(count("source_system='GBIS' AND source_key LIKE '3:999:%' AND occurrence_id IS NULL")).isEqualTo(2);
        var json = JSON.readTree(jdbc.queryForObject("SELECT raw_payload FROM transit_source_metadata WHERE source_system='GBIS' AND source_key='200'", String.class));
        assertThat(json.get("routeTypeCd").isString()).isTrue();
        assertThat(json.get("routeTypeCd").asString()).isEqualTo("014");
        assertThat(json.get("extra").asString()).isEqualTo("  value  ");
        assertThat(json.get("empty").asString()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT occurrence_id) FROM transit_source_metadata WHERE source_system='GBIS' AND occurrence_id IS NOT NULL", Integer.class)).isEqualTo(2);
        sync.syncTagoSnapshot(snapshot, TIME);
        assertThat(gbisRows()).isEqualTo(first);
        Long id = jdbc.queryForObject("SELECT id FROM transit_source_metadata WHERE source_system='GBIS' AND source_key='200'", Long.class);
        sync.syncTagoSnapshot(snapshot(2, "GGB99", "20261002", "015"), TIME.plusSeconds(60));
        assertThat(count("source_system='GBIS' AND (route_id IS NOT NULL OR occurrence_id IS NOT NULL)")).isZero();
        assertThat(jdbc.queryForObject("SELECT id FROM transit_source_metadata WHERE source_system='GBIS' AND source_key='200'", Long.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT source_version FROM transit_source_metadata WHERE id=?", String.class, id)).isEqualTo("20261002");
        assertThat(jdbc.queryForObject("SELECT collected_at FROM transit_source_metadata WHERE id=?", java.sql.Timestamp.class, id).toInstant()).isEqualTo(TIME.plusSeconds(60));
        assertThat(JSON.readTree(jdbc.queryForObject("SELECT raw_payload FROM transit_source_metadata WHERE id=?", String.class, id)).get("routeTypeCd").asString()).isEqualTo("015");
    }

    @Test
    @DisplayName("두 번째 GBIS 배치 실패는 첫 배치와 TAGO 커밋을 보존하고 cleanup을 차단하며 재시도한다")
    void rolls_back_batch_and_retries() {
        sync.syncTagoSnapshot(snapshot(2, "GGB10", "1", "014"), TIME);
        var completion = jdbc.queryForObject("SELECT last_complete_sync_at FROM bus_metadata_sync_states WHERE provider='TAGO'", java.sql.Timestamp.class);
        sync.syncRoute(new BusRouteMetadataSnapshot(TransitProvider.TAGO, "stale", "stale", "31010", List.of(new BusStopOccurrenceMetadataSnapshot("stale", "stale", null, null, 1))));
        jdbc.execute("DELETE FROM transit_source_metadata WHERE source_system='GBIS'");
        var large = snapshot(501, "GGB10", "2", "015");
        jdbc.execute("CREATE TRIGGER fail_gbis BEFORE INSERT ON transit_source_metadata FOR EACH ROW BEGIN IF NEW.source_system = 'GBIS' AND NEW.source_key = '3:999:500' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'batch failure'; END IF; END");
        try {
            assertThatThrownBy(() -> sync.syncTagoSnapshot(large, TIME.plusSeconds(60))).isInstanceOf(DataAccessException.class);
            assertThat(count("source_system='GBIS'")).isEqualTo(500);
            assertThat(count("source_system='GBIS' AND source_key='3:999:500'")).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_routes WHERE external_route_id='stale'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT last_complete_sync_at FROM bus_metadata_sync_states WHERE provider='TAGO'", java.sql.Timestamp.class)).isEqualTo(completion);
            assertThat(count("source_system='TAGO'")).isEqualTo(3);
        } finally { jdbc.execute("DROP TRIGGER fail_gbis"); }
        sync.syncTagoSnapshot(large, TIME.plusSeconds(60));
        assertThat(count("source_system='GBIS'")).isEqualTo(505);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_routes WHERE external_route_id='stale'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("손상된 GBIS 원본과 버전은 첫 TAGO DB 변경 전에 거부한다")
    void rejects_invalid_raw_before_writes() {
        var valid = snapshot(2, "GGB10", "1", "014");
        var g = valid.gbis();
        List<GbisCollectedSnapshot> invalid = new ArrayList<>();
        invalid.add(new GbisCollectedSnapshot(g.normalized(), g.rawRows().subList(1, g.rawRows().size()), "1"));
        invalid.add(new GbisCollectedSnapshot(g.normalized(), g.rawRows().subList(0, g.rawRows().size()-1), "1"));
        var duplicate = new ArrayList<>(g.rawRows()); duplicate.add(g.rawRows().getFirst()); invalid.add(new GbisCollectedSnapshot(g.normalized(), duplicate, "1"));
        invalid.add(new GbisCollectedSnapshot(g.normalized(), g.rawRows(), " "));
        var badStations = List.of(new GbisBulkMetadata.Station(1, "", "정류장"), new GbisBulkMetadata.Station(2, "10", "정류장"));
        var badRoutes = new HashMap<>(g.normalized().routes());
        badRoutes.put("200", new GbisBulkMetadata.Route("200", "300", 1, badStations));
        invalid.add(new GbisCollectedSnapshot(new GbisBulkMetadata(badRoutes), g.rawRows(), "1"));
        for (int mode = 0; mode < 5; mode++) {
            var rows = new ArrayList<>(g.rawRows());
            RawRow row = rows.get(2);
            var fields = new HashMap<>(row.fields());
            if (mode == 0) fields.put("stationId", "wrong");
            if (mode == 1) fields.put("staOrder", "9");
            if (mode == 2) fields.put("routeId", "wrong");
            rows.set(2, new RawRow(mode == 3 ? TransitSourceSystem.TAGO : row.sourceSystem(), mode == 4 ? TransitSourceDataKind.STOP : row.dataKind(), row.sourceKey(), row.externalRouteId(), row.stopOrder(), fields));
            invalid.add(new GbisCollectedSnapshot(g.normalized(), rows, "1"));
        }
        for (var bad : invalid) {
            assertThatThrownBy(() -> sync.syncTagoSnapshot(new TagoMetadataSource.TagoSnapshot(valid.normalized(), valid.rawRows(), bad), TIME)).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_routes", Integer.class)).isZero();
            assertThat(count("1=1")).isZero();
        }
    }

    @Test
    @DisplayName("Bootstrap GBIS 원본 저장 실패는 TAGO 완료 시각과 cleanup을 차단한다")
    void bootstrap_records_gbis_write_failure() {
        var archive = org.mockito.Mockito.mock(com.stopbell.transit.service.TransitMetadataArchiveService.class);
        var source = org.mockito.Mockito.mock(TagoMetadataSource.class);
        org.mockito.Mockito.when(source.provider()).thenReturn(TransitProvider.TAGO);
        org.mockito.Mockito.when(source.fetchSnapshotWithRawRows(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(archive)))
                .thenReturn(snapshot(2, "GGB10", "1", "014"));
        sync.syncRoute(new BusRouteMetadataSnapshot(TransitProvider.TAGO, "stale", "stale", "31010", List.of(new BusStopOccurrenceMetadataSnapshot("stale", "stale", null, null, 1))));
        jdbc.execute("CREATE TRIGGER fail_gbis_bootstrap BEFORE INSERT ON transit_source_metadata FOR EACH ROW BEGIN IF NEW.source_system = 'GBIS' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'failure'; END IF; END");
        try {
            var bootstrap = new com.stopbell.transit.service.BusMetadataBootstrapService(List.of(source), sync, history, archive, analysis, java.time.Clock.systemUTC());
            assertThatThrownBy(() -> bootstrap.bootstrap(TransitProvider.TAGO)).hasMessageContaining("stage=SYNC");
            Long id = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='TAGO'", Long.class);
            var failed = history.find(id);
            assertThat(failed.getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.FAILED);
            assertThat(failed.getFailureStage()).isEqualTo(com.stopbell.transit.domain.TransitCollectionFailureStage.SYNC);
            assertThat(failed.getFailureCode()).isEqualTo(com.stopbell.transit.domain.TransitCollectionFailureCode.SYNC_FAILED);
            assertThat(analysis.find(id)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_metadata_sync_states WHERE provider='TAGO'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_routes WHERE external_route_id='stale'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_routes WHERE external_route_id='GGB200'", Integer.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER fail_gbis_bootstrap"); }
    }

    @Test
    @DisplayName("TAGO 원본과 정규화 값 불일치는 DB 쓰기 전에 검증 실패로 기록한다")
    void bootstrap_rejects_invalid_tago_raw() {
        var archive = org.mockito.Mockito.mock(com.stopbell.transit.service.TransitMetadataArchiveService.class);
        var source = org.mockito.Mockito.mock(TagoMetadataSource.class);
        var valid = snapshot(2, "GGB10", "1", "014");
        var invalid = new TagoMetadataSource.TagoSnapshot(valid.normalized(), List.of(), valid.gbis());
        org.mockito.Mockito.when(source.provider()).thenReturn(TransitProvider.TAGO);
        org.mockito.Mockito.when(source.fetchSnapshotWithRawRows(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(archive))).thenReturn(invalid);
        var bootstrap = new com.stopbell.transit.service.BusMetadataBootstrapService(List.of(source), sync, history, archive, analysis, java.time.Clock.systemUTC());
        assertThatThrownBy(() -> bootstrap.bootstrap(TransitProvider.TAGO)).hasMessageContaining("stage=VALIDATION");
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='TAGO'", Long.class);
        assertThat(history.find(id).getFailureCode()).isEqualTo(com.stopbell.transit.domain.TransitCollectionFailureCode.INVALID_SOURCE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_routes", Integer.class)).isZero();
    }

    private TagoMetadataSource.TagoSnapshot snapshot(int extraStations, String tagoStop, String version, String type) {
        var stops = List.of(new BusStopOccurrenceMetadataSnapshot(tagoStop, "A", null, null, 1), new BusStopOccurrenceMetadataSnapshot(tagoStop, "B", null, null, 2));
        var tago = new CompleteBusMetadataSnapshot(TransitProvider.TAGO, List.of(new BusRouteMetadataSnapshot(TransitProvider.TAGO, "GGB200", "300", "31010", stops)));
        var raw = List.of(tagoRaw(TransitSourceDataKind.ROUTE, "GGB200", null, "{\"routeid\":\"GGB200\"}"),
                tagoRaw(TransitSourceDataKind.OCCURRENCE, "6:GGB200:1", 1, "{\"nodeid\":\"" + tagoStop + "\",\"nodeord\":1}"),
                tagoRaw(TransitSourceDataKind.OCCURRENCE, "6:GGB200:2", 2, "{\"nodeid\":\"" + tagoStop + "\",\"nodeord\":2}"));
        List<RawRow> rows = new ArrayList<>();
        List<GbisBulkMetadata.Station> matched = new ArrayList<>();
        List<GbisBulkMetadata.Station> extra = new ArrayList<>();
        rows.add(routeRaw("200", "300", type)); rows.add(routeRaw("999", "999", type));
        addStations(rows, matched, "200", "300", 2);
        addStations(rows, extra, "999", "999", extraStations);
        var normalized = new GbisBulkMetadata(Map.of("200", new GbisBulkMetadata.Route("200", "300", 1, matched), "999", new GbisBulkMetadata.Route("999", "999", 1, extra)));
        return new TagoMetadataSource.TagoSnapshot(tago, raw, new GbisCollectedSnapshot(normalized, rows, version));
    }
    private static RawRow routeRaw(String id, String name, String type) {
        return new RawRow(TransitSourceSystem.GBIS, TransitSourceDataKind.ROUTE, id, id, null, Map.of("routeId", id, "routeName", name, "turnSeq", "01", "routeTypeCd", type, "extra", "  value  ", "empty", ""));
    }
    private static void addStations(List<RawRow> rows, List<GbisBulkMetadata.Station> stations, String id, String name, int count) {
        for (int i = 1; i <= count; i++) {
            String stop = id.equals("200") ? "10" : "99";
            stations.add(new GbisBulkMetadata.Station(i, stop, "정류장"));
            rows.add(new RawRow(TransitSourceSystem.GBIS, TransitSourceDataKind.OCCURRENCE, "3:" + id + ":" + i, id, i, Map.of("routeId", id, "routeName", name, "staOrder", String.valueOf(i), "stationId", stop, "stationName", "정류장")));
        }
    }
    private static TagoMetadataSource.RawSourceRow tagoRaw(TransitSourceDataKind kind, String key, Integer order, String fields) {
        return new TagoMetadataSource.RawSourceRow(TransitSourceSystem.TAGO, kind, key, "GGB200", order, JSON.readTree(fields));
    }
    private int count(String where) { return jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE " + where, Integer.class); }
    private List<Map<String, Object>> gbisRows() { return jdbc.queryForList("SELECT * FROM transit_source_metadata WHERE source_system='GBIS' ORDER BY id"); }
}
