package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.service.BusRouteMetadataSnapshot;
import com.stopbell.transit.service.BusStopOccurrenceMetadataSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GbisBulkMetadataTest {
    private static final String ROUTES = "routeId|routeName|turnSeq|startStationName^200|300|2|wrong start";
    private static final String STATIONS = "routeId|routeName|upDown|staOrder|stationId|stationName|x|y^"
            + "200|300|상행|1|10|출발|127|37^200|300|상행|2|20|회차|127|37^"
            + "200|300|하행|3|10|종착|127|37";

    @Test
    void parses_valid_bom_and_record_delimiters() {
        GbisBulkMetadata data = parse("\uFEFF" + ROUTES, STATIONS);
        assertThat(data.routes().get("200").stations()).hasSize(3);
        assertThat(data.routes().get("200").turnSeq()).isEqualTo(2);
    }

    @Test
    void rejects_missing_header_corrupt_records_and_empty_sources() {
        for (String route : List.of("routeId|routeName^200|300", "", ROUTES + "^bad|row")) {
            assertThatThrownBy(() -> parse(route, STATIONS)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String stations : List.of("routeId|staOrder^200|1", STATIONS + "^bad", "")) {
            assertThatThrownBy(() -> parse(ROUTES, stations)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> GbisBulkMetadata.parse(
                new byte[]{(byte) 0xff}, bytes(STATIONS), "1", "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_duplicate_route_order_invalid_turn_and_missing_join() {
        assertThatThrownBy(() -> parse(ROUTES + "^200|300|2|x", STATIONS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(ROUTES, STATIONS + "^200|300|상행|2|30|중복|127|37"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(ROUTES.replace("|2|", "|4|"), STATIONS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(ROUTES, STATIONS + "^999|300|상행|4|40|미연결|127|37"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GbisBulkMetadata.parse(bytes(ROUTES), bytes(STATIONS), "1", "2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enriches_only_verified_tago_traversal_and_uses_terminal_not_route_start() {
        GbisBulkMetadata bulk = parse(ROUTES, STATIONS);
        BusRouteMetadataSnapshot matched = tago("GGB200", "300", "GGB10", "GGB20", "GGB10");
        var result = GbisDestinationEnricher.enrich(List.of(matched), bulk);
        assertThat(result.enrichedRoutes()).isEqualTo(1);
        assertThat(result.routes().getFirst().occurrences())
                .extracting(BusStopOccurrenceMetadataSnapshot::destinationName)
                .containsExactly("회차", "종착", "종착");

        var rejected = GbisDestinationEnricher.enrich(List.of(
                tago("GGB200", "301", "GGB10", "GGB20", "GGB10"),
                tago("GGB200", "300", "GGB10", "GGB20"),
                new BusRouteMetadataSnapshot(TransitProvider.TAGO, "GGB200", "300", "31010", List.of(
                        new BusStopOccurrenceMetadataSnapshot("GGB10", "A", null, null, 1),
                        new BusStopOccurrenceMetadataSnapshot("GGB20", "B", null, null, 2),
                        new BusStopOccurrenceMetadataSnapshot("GGB10", "C", null, null, 4))),
                tago("GGB200", "300", "GGB10", "GGB20", "GGB99"),
                tago("GGB999", "300", "GGB10", "GGB20", "GGB10")
        ), bulk);
        assertThat(rejected.enrichedRoutes()).isZero();
        assertThat(rejected.routes()).hasSize(5);
        assertThat(rejected.skippedRoutes()).containsEntry("route_name", 1)
                .containsEntry("occurrence_count", 1).containsEntry("order", 1)
                .containsEntry("stop_id", 1)
                .containsEntry("candidate_missing", 1);
    }

    @Test
    void gbis_only_route_never_expands_tago_scope() {
        GbisBulkMetadata bulk = parse(ROUTES + "^999|999|1|start",
                STATIONS + "^999|999|상행|1|99|only GBIS|127|37");
        var result = GbisDestinationEnricher.enrich(
                List.of(tago("GGB200", "300", "GGB10", "GGB20", "GGB10")), bulk);
        assertThat(result.routes()).singleElement()
                .satisfies(route -> assertThat(route.externalRouteId()).isEqualTo("GGB200"));
        assertThat(bulk.routes()).hasSize(2);
    }

    @Test
    @DisplayName("원본 전체 필드와 문자열을 보존하고 반복 정류장을 순번으로 식별한다")
    void preserves_raw_fields_and_occurrence_identity() {
        String routes = "routeId|routeName|turnSeq|routeTypeCd|extra|empty^ 0200 | 300 |02|014|  value  |";
        String stations = "routeId|routeName|upDown|staOrder|stationId|stationName|x|y|extra^"
                + " 0200 | 300 | 상행 |01|10|출발|127|37|  x  ^"
                + "0200|300|하행|02|10|종착|127|37|";
        var snapshot = GbisBulkMetadata.parseWithRaw(bytes(routes), bytes(stations), "20261001", "20261001");
        assertThat(snapshot.sourceVersion()).isEqualTo("20261001");
        assertThat(snapshot.normalized()).isEqualTo(GbisBulkMetadata.parse(bytes(routes), bytes(stations), "20261001", "20261001"));
        assertThat(snapshot.rawRows()).hasSize(3);
        var route = snapshot.rawRows().get(0);
        assertThat(route.sourceSystem()).isEqualTo(TransitSourceSystem.GBIS);
        assertThat(route.dataKind()).isEqualTo(TransitSourceDataKind.ROUTE);
        assertThat(route.sourceKey()).isEqualTo("0200");
        assertThat(route.fields()).containsOnlyKeys("routeId", "routeName", "turnSeq", "routeTypeCd", "extra", "empty")
                .containsEntry("routeId", " 0200 ").containsEntry("turnSeq", "02")
                .containsEntry("routeTypeCd", "014").containsEntry("extra", "  value  ").containsEntry("empty", "");
        var first = snapshot.rawRows().get(1);
        var second = snapshot.rawRows().get(2);
        assertThat(first.dataKind()).isEqualTo(TransitSourceDataKind.OCCURRENCE);
        assertThat(first.sourceKey()).isEqualTo("4:0200:1");
        assertThat(second.sourceKey()).isEqualTo("4:0200:2");
        assertThat(first.fields()).containsOnlyKeys("routeId", "routeName", "upDown", "staOrder", "stationId", "stationName", "x", "y", "extra")
                .containsEntry("staOrder", "01").containsEntry("extra", "  x  ");
        assertThat(second.fields()).containsEntry("stationId", "10").containsEntry("extra", "");
        assertThatThrownBy(() -> GbisBulkMetadata.parseWithRaw(bytes(routes), bytes(stations + "^0200|300|하행|01|10|중복|127|37|"), "1", "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("원본 수집에서도 중복 헤더와 빈 행 및 잘못된 버전을 거부한다")
    void raw_collection_rejects_invalid_input() {
        for (String version : List.of("", " ")) {
            assertThatThrownBy(() -> GbisBulkMetadata.parseWithRaw(bytes(ROUTES), bytes(STATIONS), version, version))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> GbisBulkMetadata.parseWithRaw(bytes(ROUTES), bytes(STATIONS), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        for (String route : List.of("routeId|routeName|turnSeq|routeId^200|300|2|200", ROUTES + "^^bad", ROUTES + "^201|300|x|a")) {
            assertThatThrownBy(() -> GbisBulkMetadata.parseWithRaw(bytes(route), bytes(STATIONS), "1", "1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static BusRouteMetadataSnapshot tago(String routeId, String name, String... stopIds) {
        java.util.ArrayList<BusStopOccurrenceMetadataSnapshot> stops = new java.util.ArrayList<>();
        for (int i = 0; i < stopIds.length; i++) {
            stops.add(new BusStopOccurrenceMetadataSnapshot(stopIds[i], "TAGO " + i, null, null, i + 1));
        }
        return new BusRouteMetadataSnapshot(TransitProvider.TAGO, routeId, name, "31010", stops);
    }

    private static GbisBulkMetadata parse(String routes, String stations) {
        return GbisBulkMetadata.parse(bytes(routes), bytes(stations), "1", "1");
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
