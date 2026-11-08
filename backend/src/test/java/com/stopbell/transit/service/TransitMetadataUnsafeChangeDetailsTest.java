package com.stopbell.transit.service;

import static com.stopbell.transit.domain.TransitProvider.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransitMetadataUnsafeChangeDetailsTest {
    @Test @DisplayName("반복 방문과 위치 변경 및 공유 정류장 충돌의 근거를 결정적으로 보존한다")
    void builds_deterministic_immutable_evidence() {
        var old = new BusMetadataDiffService.CurrentState(List.of(
                route("r", occurrence("s", "old", 1), occurrence("s", "old", 2)),
                route("removed", occurrence("orphan", "orphan", 1))), List.of(
                new BusMetadataDiffService.Stop(TAGO, "s", "old", null, null),
                new BusMetadataDiffService.Stop(TAGO, "orphan", "orphan", null, null)));
        var incoming = new CompleteBusMetadataSnapshot(TAGO, List.of(
                route("r", occurrence("new", "new", 1), occurrence("s", "one", 2)),
                route("x", occurrence("s", "two", 1))));
        var diff = new BusMetadataDiffService().analyze(old, incoming);
        var details = TransitMetadataUnsafeChangeDetails.build(diff, incoming);
        assertThat(details).extracting(TransitMetadataUnsafeChangeDetails.Detail::reason).containsExactly(
                BusMetadataSafetyGuard.Reason.OCCURRENCE_REMOVAL, BusMetadataSafetyGuard.Reason.OCCURRENCE_REMOVAL,
                BusMetadataSafetyGuard.Reason.ROUTE_REMOVAL, BusMetadataSafetyGuard.Reason.SHARED_STOP_CONFLICT,
                BusMetadataSafetyGuard.Reason.STOP_CLEANUP);
        assertThat(details).anyMatch(d -> "r".equals(d.routeId()) && "s".equals(d.stopId()) && d.stopOrder() == 1 && "new".equals(d.replacementStopId()));
        var conflict = details.stream().filter(d -> d.reason() == BusMetadataSafetyGuard.Reason.SHARED_STOP_CONFLICT).findFirst().orElseThrow();
        assertThat(conflict.variants()).extracting(TransitMetadataUnsafeChangeDetails.Variant::stopName).containsExactly("one", "two");
        assertThat(details).allMatch(d -> d.provider() == TAGO);
        assertThatThrownBy(() -> details.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> conflict.variants().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(TransitMetadataUnsafeChangeDetails.fingerprint(details)).isEqualTo(TransitMetadataUnsafeChangeDetails.fingerprint(TransitMetadataUnsafeChangeDetails.build(diff, incoming)));
        var seoul = new CompleteBusMetadataSnapshot(SEOUL_BUS, List.of(new BusRouteMetadataSnapshot(SEOUL_BUS, "r", "1", null, List.of(occurrence("s", "S", 1)))));
        assertThatThrownBy(() -> TransitMetadataUnsafeChangeDetails.build(diff, seoul)).isInstanceOf(IllegalArgumentException.class);
    }
    private static BusRouteMetadataSnapshot route(String id, BusStopOccurrenceMetadataSnapshot... stops) {
        return new BusRouteMetadataSnapshot(TAGO, id, "1", "31010", List.of(stops));
    }
    private static BusStopOccurrenceMetadataSnapshot occurrence(String id, String name, int order) {
        return new BusStopOccurrenceMetadataSnapshot(id, name, BigDecimal.valueOf(37), BigDecimal.valueOf(127), order);
    }
}
