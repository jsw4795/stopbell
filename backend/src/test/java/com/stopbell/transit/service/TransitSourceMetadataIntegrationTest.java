package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import com.stopbell.support.LatestSchemaIntegrationTest;
import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.service.TransitSourceMetadataService.SourceRecord;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlMergeMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SqlMergeMode(SqlMergeMode.MergeMode.MERGE)
@Sql(scripts = "/reset-latest-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/reset-latest-schema.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class TransitSourceMetadataIntegrationTest extends LatestSchemaIntegrationTest {

    @Autowired TransitSourceMetadataService service;
    @Autowired JdbcTemplate jdbc;

    private static final Instant COLLECTED = Instant.parse("2026-11-07T00:00:00Z");

    @Test
    void saves_separate_sources_updates_in_place_and_rolls_back_failed_batch() {
        jdbc.update("INSERT INTO bus_routes (provider, external_route_id, route_number) VALUES ('SEOUL_BUS', 'source-test', '100')");
        Long routeId = jdbc.queryForObject("SELECT id FROM bus_routes WHERE external_route_id='source-test'", Long.class);
        assertThat(jdbc.queryForObject("SELECT route_type FROM bus_routes WHERE id=?", String.class, routeId)).isNull();

        SourceRecord seoul = new SourceRecord(TransitSourceSystem.SEOUL_TDATA, TransitSourceDataKind.ROUTE,
                "route-1", routeId, "{\"노선유형\":\"간선\",\"거리\":\"1.2\"}", null, COLLECTED);
        SourceRecord tago = new SourceRecord(TransitSourceSystem.TAGO, TransitSourceDataKind.ROUTE,
                "route-1", null, "{\"routetp\":\"일반버스\"}", "v1", COLLECTED);
        service.saveAll(List.of(seoul, tago));
        var first = service.find(TransitSourceSystem.SEOUL_TDATA, TransitSourceDataKind.ROUTE, "route-1").orElseThrow();
        assertThat(first.getRouteId()).isEqualTo(routeId);
        assertThat(first.getRawPayload()).contains("노선유형", "간선", "거리");
        assertThat(service.find(TransitSourceSystem.TAGO, TransitSourceDataKind.ROUTE, "route-1"))
                .get().extracting("rawPayload").asString().contains("일반버스");
        service.saveAll(List.of(seoul));
        var repeated = service.find(TransitSourceSystem.SEOUL_TDATA, TransitSourceDataKind.ROUTE, "route-1").orElseThrow();
        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(repeated.getUpdatedAt()).isEqualTo(first.getUpdatedAt());

        SourceRecord changed = new SourceRecord(TransitSourceSystem.SEOUL_TDATA, TransitSourceDataKind.ROUTE,
                "route-1", routeId, "{\"노선유형\":\"지선\"}", "v2", COLLECTED);
        service.saveAll(List.of(changed));
        var updated = service.find(TransitSourceSystem.SEOUL_TDATA, TransitSourceDataKind.ROUTE, "route-1").orElseThrow();
        assertThat(updated.getId()).isEqualTo(first.getId());
        assertThat(updated.getRawPayload()).contains("지선");

        SourceRecord missingStop = new SourceRecord(TransitSourceSystem.GBIS, TransitSourceDataKind.STOP,
                "missing", Long.MAX_VALUE, "{\"stationId\":\"missing\"}", null, COLLECTED);
        assertThatThrownBy(() -> service.saveAll(List.of(seoul, missingStop))).isInstanceOf(DataAccessException.class);
        assertThat(service.find(TransitSourceSystem.SEOUL_TDATA, TransitSourceDataKind.ROUTE, "route-1")
                .orElseThrow().getRawPayload()).contains("지선");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT id FROM bus_routes WHERE external_route_id='source-test'", Long.class))
                .isEqualTo(routeId);
        jdbc.update("INSERT INTO bus_stops (provider, external_stop_id, stop_name) VALUES ('SEOUL_BUS', 'stop-test', 'stop')");
        Long stopId = jdbc.queryForObject("SELECT id FROM bus_stops WHERE external_stop_id='stop-test'", Long.class);
        jdbc.update("INSERT INTO bus_route_stop_occurrences (route_id, stop_id, stop_order) VALUES (?, ?, 1)", routeId, stopId);
        Long occurrenceId = jdbc.queryForObject("SELECT id FROM bus_route_stop_occurrences WHERE route_id=?", Long.class, routeId);
        SourceRecord stop = new SourceRecord(TransitSourceSystem.SEOUL_TDATA, TransitSourceDataKind.STOP,
                "stop-test", stopId, "{\"정류장번호\":\"001\"}", null, COLLECTED);
        SourceRecord occurrence = new SourceRecord(TransitSourceSystem.GBIS, TransitSourceDataKind.OCCURRENCE,
                "route-1:1", occurrenceId, "{\"staOrder\":1}", "v1", COLLECTED);
        service.saveAll(List.of(stop, occurrence));
        assertThat(service.find(TransitSourceSystem.SEOUL_TDATA, TransitSourceDataKind.STOP, "stop-test")
                .orElseThrow().getStopId()).isEqualTo(stopId);
        assertThat(service.find(TransitSourceSystem.GBIS, TransitSourceDataKind.OCCURRENCE, "route-1:1")
                .orElseThrow().getOccurrenceId()).isEqualTo(occurrenceId);
        jdbc.update("DELETE FROM bus_route_stop_occurrences WHERE id=?", occurrenceId);
        assertThat(service.find(TransitSourceSystem.GBIS, TransitSourceDataKind.OCCURRENCE, "route-1:1")
                .orElseThrow().getOccurrenceId()).isNull();
    }
}
