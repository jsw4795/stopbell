package com.stopbell.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.metadata.TagoMetadataSource;
import com.stopbell.transit.metadata.TagoMetadataSource.RawSourceRow;
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
class TagoRawMetadataSyncIntegrationTest {
    private static final Instant TIME = Instant.parse("2026-11-07T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired BusMetadataSyncService sync;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        SharedMySqlExtension.database(registry, "tago_raw_sync");
    }

    @BeforeEach
    void clear_metadata() {
        jdbc.update("DELETE FROM transit_source_metadata");
        jdbc.update("DELETE FROM bus_route_stop_occurrences");
        jdbc.update("DELETE FROM bus_routes");
        jdbc.update("DELETE FROM bus_stops");
        jdbc.update("DELETE FROM bus_metadata_sync_states");
    }

    @Test
    @DisplayName("TAGO 원본과 반복 경유 FK를 저장하고 재처리와 occurrence 교체 시 ID 정책을 지킨다")
    void saves_and_reprocesses_linked_raw_rows() {
        var collected = snapshot("s1", "일반");
        sync.syncTagoSnapshot(collected, TIME);
        var first = rows();
        assertThat(first).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE source_system='TAGO' AND data_kind='STOP'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata m JOIN bus_routes r ON m.route_id=r.id WHERE m.data_kind='ROUTE' AND m.source_key=r.external_route_id", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata m JOIN bus_route_stop_occurrences o ON m.occurrence_id=o.id WHERE m.data_kind='OCCURRENCE'", Integer.class)).isEqualTo(3);
        var routeJson = JSON.readTree(jdbc.queryForObject("SELECT raw_payload FROM transit_source_metadata WHERE source_key='r1' AND data_kind='ROUTE'", String.class));
        assertThat(routeJson.get("routetp").asString()).isEqualTo("일반");
        assertThat(routeJson.get("number").isNumber()).isTrue();
        assertThat(routeJson.get("text").isString()).isTrue();
        assertThat(routeJson.get("nested").get("flag").asBoolean()).isTrue();
        assertThat(routeJson.get("nothing").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT destination_name FROM bus_route_stop_occurrences o JOIN bus_routes r ON o.route_id=r.id WHERE r.external_route_id='r1' AND o.stop_order=1", String.class)).isEqualTo("목적지");
        assertThat(JSON.readTree(jdbc.queryForObject("SELECT raw_payload FROM transit_source_metadata WHERE source_key='2:r1:1'", String.class)).has("destinationName")).isFalse();
        Long routeId = id("SELECT id FROM bus_routes WHERE external_route_id='r1'");
        Long occurrenceId = id("SELECT occurrence_id FROM transit_source_metadata WHERE source_key='2:r1:1'");
        Long sourceId = id("SELECT id FROM transit_source_metadata WHERE source_key='2:r1:1'");
        sync.syncTagoSnapshot(collected, TIME);
        assertThat(rows()).isEqualTo(first);
        assertThat(id("SELECT id FROM bus_routes WHERE external_route_id='r1'")).isEqualTo(routeId);
        assertThat(id("SELECT occurrence_id FROM transit_source_metadata WHERE source_key='2:r1:1'")).isEqualTo(occurrenceId);
        sync.syncTagoSnapshot(collected, TIME.plusSeconds(60));
        assertThat(id("SELECT id FROM transit_source_metadata WHERE source_key='2:r1:1'")).isEqualTo(sourceId);
        assertThat(jdbc.queryForObject("SELECT collected_at FROM transit_source_metadata WHERE id=?", java.sql.Timestamp.class, sourceId).toInstant()).isEqualTo(TIME.plusSeconds(60));
        sync.syncTagoSnapshot(snapshot("new-stop", "변경"), TIME.plusSeconds(120));
        assertThat(id("SELECT id FROM transit_source_metadata WHERE source_key='2:r1:1'")).isEqualTo(sourceId);
        assertThat(id("SELECT occurrence_id FROM transit_source_metadata WHERE source_key='2:r1:1'")).isNotEqualTo(occurrenceId);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_payload,'$.routetp')) FROM transit_source_metadata WHERE source_key='r1' AND data_kind='ROUTE'", String.class)).isEqualTo("변경");
        assertThat(jdbc.queryForObject("SELECT collected_at FROM transit_source_metadata WHERE id=?", java.sql.Timestamp.class, sourceId).toInstant()).isEqualTo(TIME.plusSeconds(120));
    }

    @Test
    @DisplayName("원본 저장 실패는 해당 노선만 롤백하고 cleanup과 완료 시각을 막으며 재시도할 수 있다")
    void rolls_back_failed_route_and_retries() {
        sync.syncTagoSnapshot(snapshot("s1", "기존"), TIME);
        var beforeRoute = jdbc.queryForList("SELECT * FROM bus_routes WHERE external_route_id='r2'");
        var beforeRaw = jdbc.queryForList("SELECT * FROM transit_source_metadata WHERE source_key IN ('r2','2:r2:1') ORDER BY id");
        var completion = jdbc.queryForObject("SELECT last_complete_sync_at FROM bus_metadata_sync_states WHERE provider='TAGO'", java.sql.Timestamp.class);
        sync.syncRoute(route("stale", "stale-stop"));
        jdbc.execute("""
                CREATE TRIGGER fail_tago_raw BEFORE INSERT ON transit_source_metadata
                FOR EACH ROW BEGIN
                  IF NEW.source_key = '2:r2:1' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'raw failure'; END IF;
                END
                """);
        var changed = snapshot("s1", "변경");
        try {
            assertThatThrownBy(() -> sync.syncTagoSnapshot(changed, TIME.plusSeconds(60))).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_payload,'$.routetp')) FROM transit_source_metadata WHERE source_key='r1'", String.class)).isEqualTo("변경");
            assertThat(jdbc.queryForList("SELECT * FROM bus_routes WHERE external_route_id='r2'")).isEqualTo(beforeRoute);
            assertThat(jdbc.queryForList("SELECT * FROM transit_source_metadata WHERE source_key IN ('r2','2:r2:1') ORDER BY id")).isEqualTo(beforeRaw);
            assertThat(id("SELECT id FROM bus_routes WHERE external_route_id='stale'")).isNotNull();
            assertThat(jdbc.queryForObject("SELECT last_complete_sync_at FROM bus_metadata_sync_states WHERE provider='TAGO'", java.sql.Timestamp.class)).isEqualTo(completion);
        } finally {
            jdbc.execute("DROP TRIGGER fail_tago_raw");
        }
        sync.syncTagoSnapshot(changed, TIME.plusSeconds(60));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_routes WHERE external_route_id='stale'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("원본 누락·중복·식별자·순서·출처·종류 오류는 첫 DB 변경 전에 거부한다")
    void rejects_invalid_mapping_before_any_write() {
        var valid = snapshot("s1", "일반");
        sync.syncTagoSnapshot(valid, TIME);
        var before = database();
        List<List<RawSourceRow>> invalid = new ArrayList<>();
        invalid.add(valid.rawRows().subList(1, valid.rawRows().size()));
        invalid.add(valid.rawRows().stream().filter(row -> !row.sourceKey().equals("2:r1:1")).toList());
        var duplicate = new ArrayList<>(valid.rawRows()); duplicate.add(valid.rawRows().getFirst()); invalid.add(duplicate);
        var stopMismatch = new ArrayList<>(valid.rawRows()); stopMismatch.set(1, raw(TransitSourceDataKind.OCCURRENCE, "2:r1:1", "r1", 1, "{\"nodeid\":\"wrong\",\"nodeord\":1}")); invalid.add(stopMismatch);
        var routeMismatch = new ArrayList<>(valid.rawRows()); routeMismatch.set(0, raw(TransitSourceDataKind.ROUTE, "r1", "r1", null, "{\"routeid\":\"wrong\"}")); invalid.add(routeMismatch);
        var orderMismatch = new ArrayList<>(valid.rawRows()); orderMismatch.set(1, raw(TransitSourceDataKind.OCCURRENCE, "2:r1:1", "r1", 1, "{\"nodeid\":\"s1\",\"nodeord\":2}")); invalid.add(orderMismatch);
        var extra = new ArrayList<>(valid.rawRows()); extra.add(raw(TransitSourceDataKind.ROUTE, "extra", "extra", null, "{\"routeid\":\"extra\"}")); invalid.add(extra);
        var wrongSource = new ArrayList<>(valid.rawRows()); var row = valid.rawRows().getFirst(); wrongSource.set(0, new RawSourceRow(TransitSourceSystem.GBIS, row.dataKind(), row.sourceKey(), row.externalRouteId(), row.stopOrder(), row.fields())); invalid.add(wrongSource);
        var wrongKind = new ArrayList<>(valid.rawRows()); wrongKind.set(0, raw(TransitSourceDataKind.STOP, "r1", "r1", null, "{}")); invalid.add(wrongKind);
        var nonObject = new ArrayList<>(valid.rawRows()); nonObject.set(0, raw(TransitSourceDataKind.ROUTE, "r1", "r1", null, "[]")); invalid.add(nonObject);
        for (var rawRows : invalid) {
            assertThatThrownBy(() -> sync.syncTagoSnapshot(new TagoMetadataSource.TagoSnapshot(valid.normalized(), rawRows), TIME.plusSeconds(60))).isInstanceOf(IllegalStateException.class);
            assertThat(database()).isEqualTo(before);
        }
    }

    private TagoMetadataSource.TagoSnapshot snapshot(String stop, String type) {
        var second = route("r2", "s2");
        second = new BusRouteMetadataSnapshot(TransitProvider.TAGO, "r2", type, "31010", second.occurrences());
        return new TagoMetadataSource.TagoSnapshot(new CompleteBusMetadataSnapshot(TransitProvider.TAGO,
                List.of(route("r1", stop), second)), List.of(
                raw(TransitSourceDataKind.ROUTE, "r1", "r1", null, "{\"routeid\":\"r1\",\"routeno\":123,\"routetp\":\"" + type + "\",\"number\":4,\"text\":\"4\",\"nested\":{\"flag\":true},\"nothing\":null}"),
                raw(TransitSourceDataKind.OCCURRENCE, "2:r1:1", "r1", 1, "{\"nodeid\":\"" + stop + "\",\"nodeord\":1}"),
                raw(TransitSourceDataKind.OCCURRENCE, "2:r1:2", "r1", 2, "{\"nodeid\":\"" + stop + "\",\"nodeord\":2}"),
                raw(TransitSourceDataKind.ROUTE, "r2", "r2", null, "{\"routeid\":\"r2\",\"routeno\":\"2\"}"),
                raw(TransitSourceDataKind.OCCURRENCE, "2:r2:1", "r2", 1, "{\"nodeid\":\"s2\",\"nodeord\":1}")));
    }

    private BusRouteMetadataSnapshot route(String id, String stop) {
        var stops = new ArrayList<BusStopOccurrenceMetadataSnapshot>();
        stops.add(new BusStopOccurrenceMetadataSnapshot(stop, "정류장", null, null, 1, "목적지"));
        if (id.equals("r1")) stops.add(new BusStopOccurrenceMetadataSnapshot(stop, "정류장", null, null, 2));
        return new BusRouteMetadataSnapshot(TransitProvider.TAGO, id, "번호", "31010", stops);
    }
    private RawSourceRow raw(TransitSourceDataKind kind, String key, String route, Integer order, String fields) {
        return new RawSourceRow(TransitSourceSystem.TAGO, kind, key, route, order, JSON.readTree(fields));
    }
    private Long id(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private List<Map<String, Object>> rows() { return jdbc.queryForList("SELECT * FROM transit_source_metadata ORDER BY id"); }
    private List<List<Map<String, Object>>> database() { return List.of(rows(), jdbc.queryForList("SELECT * FROM bus_routes ORDER BY id"), jdbc.queryForList("SELECT * FROM bus_stops ORDER BY id"), jdbc.queryForList("SELECT * FROM bus_route_stop_occurrences ORDER BY id"), jdbc.queryForList("SELECT * FROM bus_metadata_sync_states ORDER BY provider")); }
}
