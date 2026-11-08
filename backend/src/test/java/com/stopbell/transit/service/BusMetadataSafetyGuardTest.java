package com.stopbell.transit.service;

import static com.stopbell.transit.domain.TransitProvider.*;
import static com.stopbell.transit.service.BusMetadataSafetyGuard.Reason.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusMetadataSafetyGuardTest {
    private final BusMetadataDiffService diff = new BusMetadataDiffService();
    private final BusMetadataSafetyGuard guard = new BusMetadataSafetyGuard();

    private BusStopOccurrenceMetadataSnapshot stop(String id, int order, String name, String latitude, String destination) {
        return new BusStopOccurrenceMetadataSnapshot(id, name, new BigDecimal(latitude), new BigDecimal("127"), order, destination);
    }
    private BusRouteMetadataSnapshot route(com.stopbell.transit.domain.TransitProvider provider, String id, String number,
            BusStopOccurrenceMetadataSnapshot... stops) {
        return new BusRouteMetadataSnapshot(provider, id, number, provider == TAGO ? "31010" : null, List.of(stops));
    }
    private BusMetadataSafetyGuard.Result evaluate(BusMetadataDiffService.CurrentState current, BusRouteMetadataSnapshot... routes) {
        var result = diff.analyze(current, new CompleteBusMetadataSnapshot(routes[0].provider(), List.of(routes)));
        return guard.evaluate(new TransitMetadataDiffAnalysisService.Analysis(null, result));
    }
    private BusMetadataDiffService.Stop existing(com.stopbell.transit.domain.TransitProvider provider, String id) {
        return new BusMetadataDiffService.Stop(provider, id, "A", new BigDecimal("37"), new BigDecimal("127"));
    }

    @Test @DisplayName("최초 수집과 동일 데이터 및 비파괴적 추가와 수정은 허용한다")
    void permits_fresh_and_non_destructive_changes() {
        var old = route(TAGO, "r", "1", stop("a", 1, "A", "37", "old"));
        var current = new BusMetadataDiffService.CurrentState(List.of(old), List.of(existing(TAGO, "a")));
        assertThat(evaluate(new BusMetadataDiffService.CurrentState(List.of(), List.of()), old).decision()).isEqualTo(BusMetadataSafetyGuard.Decision.ALLOW);
        assertThat(evaluate(current, old).decision()).isEqualTo(BusMetadataSafetyGuard.Decision.ALLOW);
        var modified = route(TAGO, "r", "2", stop("a", 1, "New", "38", "new"), stop("b", 2, "B", "37", null));
        var added = route(TAGO, "new", "3", stop("b", 1, "B", "37", null));
        assertThat(evaluate(current, modified, added).decision()).isEqualTo(BusMetadataSafetyGuard.Decision.ALLOW);
    }

    @Test @DisplayName("삭제와 순번 변경 및 공유 정류장 충돌의 모든 사유를 집계한다")
    void blocks_all_structural_risks_and_preserves_immutable_result() {
        var old = route(TAGO, "r", "1", stop("a", 1, "A", "37", null));
        var gone = route(TAGO, "gone", "9", stop("orphan", 1, "A", "37", null));
        var current = new BusMetadataDiffService.CurrentState(List.of(old, gone), List.of(existing(TAGO, "a"), existing(TAGO, "orphan")));
        var moved = route(TAGO, "r", "1", stop("a", 2, "A", "37", null));
        var conflict = route(TAGO, "new", "2", stop("a", 1, "Different", "37", null));
        var result = evaluate(current, moved, conflict);
        assertThat(result.decision()).isEqualTo(BusMetadataSafetyGuard.Decision.BLOCK);
        assertThat(result.reasons()).containsEntry(ROUTE_REMOVAL, 1).containsEntry(OCCURRENCE_REMOVAL, 2)
                .containsEntry(STOP_CLEANUP, 1).containsEntry(SHARED_STOP_CONFLICT, 1);
        assertThatThrownBy(() -> result.reasons().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test @DisplayName("다른 Provider의 데이터는 위험 후보에 포함하지 않는다")
    void isolates_providers_and_detects_existing_orphans() {
        var other = route(TAGO, "r", "1", stop("a", 1, "A", "37", null));
        var seoul = route(SEOUL_BUS, "s", "1", stop("s", 1, "A", "37", null));
        var current = new BusMetadataDiffService.CurrentState(List.of(other), List.of(existing(TAGO, "a")));
        assertThat(evaluate(current, seoul).decision()).isEqualTo(BusMetadataSafetyGuard.Decision.ALLOW);
        var withOrphan = new BusMetadataDiffService.CurrentState(List.of(other), List.of(existing(TAGO, "a"), existing(TAGO, "orphan")));
        assertThat(evaluate(withOrphan, other).reasons()).containsOnlyKeys(STOP_CLEANUP);
    }

    @Test @DisplayName("각 위험 후보는 단독으로 존재해도 차단한다")
    void blocks_each_reason_independently() {
        for (var reason : BusMetadataSafetyGuard.Reason.values()) {
            var key = new BusMetadataDiffService.OccurrenceKey("r", "s", 1);
            var result = new BusMetadataDiffService.Result(TAGO, List.of(), List.of(),
                    reason == ROUTE_REMOVAL ? List.of("r") : List.of(), List.of(), List.of(), List.of(),
                    reason == STOP_CLEANUP ? List.of("s") : List.of(), List.of(),
                    reason == SHARED_STOP_CONFLICT ? List.of("s") : List.of(), List.of(),
                    reason == OCCURRENCE_REMOVAL ? List.of(key) : List.of(), List.of(), List.of(), List.of());
            var decision = guard.evaluate(new TransitMetadataDiffAnalysisService.Analysis(null, result));
            assertThat(decision.decision()).isEqualTo(BusMetadataSafetyGuard.Decision.BLOCK);
            assertThat(decision.reasons()).containsOnlyKeys(reason).containsEntry(reason, 1);
        }
    }
}
