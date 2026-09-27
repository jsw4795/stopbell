package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.service.BusRouteMetadataSnapshot;
import com.stopbell.transit.service.BusStopOccurrenceMetadataSnapshot;
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
