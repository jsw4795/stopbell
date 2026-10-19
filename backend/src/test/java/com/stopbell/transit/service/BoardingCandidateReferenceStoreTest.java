package com.stopbell.transit.service;

import static com.stopbell.transit.service.BoardingCandidateTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.mapper.BoardingCandidateMapper;
import com.stopbell.transit.service.BoardingCandidateReferenceStore.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoardingCandidateReferenceStoreTest {
    private final MutableClock clock = new MutableClock();
    private final BoardingCandidateReferenceStore store = new BoardingCandidateReferenceStore(clock, 2);
    private final BoardingRouteSnapshot route = route(TransitProvider.TAGO, 12L);
    private final Context context = new Context(7L, 1L, 14L, 12L);

    @Test
    @DisplayName("opaque reference는 User와 Route와 목적 및 승차 occurrence 문맥에 묶인다")
    void reference_is_bound_to_context() {
        String ref = issue("A", context);
        assertThat(ref).isNotEqualTo("A").isNotEqualTo("0");
        assertThat(store.resolve(ref, context)).isPresent();
        for (Context other : List.of(new Context(8L, 1L, 14L, 12L), new Context(7L, 2L, 14L, 12L),
                new Context(7L, 1L, 13L, 12L), new Context(7L, 1L, 14L, null))) {
            assertThat(store.resolve(ref, other)).isEmpty();
        }
        var snapshot = store.resolve(ref, context).orElseThrow();
        assertThat(snapshot.metadataFingerprint()).isEqualTo(route.fingerprint());
        assertThat(snapshot.rosterVehicle().observedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("원래 관측 60초 경계는 유지하고 초과 시 재조회가 필요하다")
    void freshness_boundary_and_expiry() {
        String ref = issue("A", context);
        clock.now = NOW.plusSeconds(60);
        assertThat(store.resolve(ref, context)).isPresent();
        clock.now = clock.now.plusNanos(1);
        assertThat(store.resolve(ref, context)).isEmpty();
    }

    @Test
    @DisplayName("서울의 더 오래된 roster 제공시각을 detail 성공으로 연장하지 않는다")
    void expiry_uses_original_provider_time() {
        var roster = BoardingCandidateMapper.roster(roster("id", "A", "20261019115930"), NOW);
        var detail = BoardingCandidateMapper.detail(detail("id", "A", "s2", 20, DATA_TIME), NOW);
        String ref = store.issue(context, route(TransitProvider.SEOUL_BUS, 12L), roster, detail);
        clock.now = NOW.plusSeconds(31);
        assertThat(store.resolve(ref, context)).isEmpty();
    }

    @Test
    @DisplayName("bounded memory의 오래된 reference는 eviction되며 다른 사용자 reference에는 접근할 수 없다")
    void bounded_eviction() {
        String first = issue("A", context);
        String second = issue("B", context);
        String third = issue("C", context);
        assertThat(store.resolve(first, context)).isEmpty();
        assertThat(store.resolve(second, context)).isPresent();
        assertThat(store.resolve(third, context)).isPresent();
    }

    @Test
    @DisplayName("동일 화면 문맥을 교체해도 다른 사용자와 목적지 reference는 유지한다")
    void replace_only_exact_context() {
        String original = issue("A", context);
        Context other = new Context(8L, 1L, 14L, 12L);
        String otherRef = issue("B", other);
        store.replace(context);
        assertThat(store.resolve(original, context)).isEmpty();
        assertThat(store.resolve(otherRef, other)).isPresent();
    }

    @Test
    @DisplayName("재시작 후 메모리 reference는 복원되지 않는다")
    void restart_requires_lookup() {
        String ref = issue("A", context);
        assertThat(new BoardingCandidateReferenceStore(clock).resolve(ref, context)).isEmpty();
    }

    @Test
    @DisplayName("metadata fingerprint는 이름 변경을 허용하고 occurrence 의미와 순서 및 Route 문맥 변경을 구분한다")
    void structural_fingerprint() {
        var renamed = new BoardingRouteSnapshot(1L, TransitProvider.TAGO, "external-route", "31010", 14L, 12L,
                route.traversal().stream().map(item -> new BoardingRouteSnapshot.Occurrence(
                        item.id(), item.externalStopId(), item.order(), "새 이름")).toList(), "7000");
        assertThat(renamed.fingerprint()).isEqualTo(route.fingerprint());
        var changed = new BoardingRouteSnapshot(1L, TransitProvider.TAGO, "external-route", "31010", 14L, 12L,
                List.of(new BoardingRouteSnapshot.Occurrence(11L, "other-stop", 10, "첫 정류장")), "7000");
        assertThat(changed.fingerprint()).isNotEqualTo(route.fingerprint());
        var changedRoute = new BoardingRouteSnapshot(1L, TransitProvider.TAGO, "other-route", "31010", 14L, 12L,
                route.traversal(), "7000");
        assertThat(changedRoute.fingerprint()).isNotEqualTo(route.fingerprint());
    }

    @Test
    @DisplayName("미래와 만료 관측으로는 사용할 수 있는 reference를 발급하지 않는다")
    void invalid_observation_cannot_issue_reference() {
        var future = BoardingCandidateMapper.tago(tago("A", "s1", 10), NOW.plusSeconds(1));
        assertThat(store.issue(context, route, future, null)).isNull();
        var stale = BoardingCandidateMapper.tago(tago("A", "s1", 10), NOW.minusSeconds(61));
        assertThat(store.issue(context, route, stale, null)).isNull();
    }

    @Test
    @DisplayName("확정 요청은 authenticated User로만 reference를 찾아 원래 노선과 목적 문맥을 서버에서 복원한다")
    void resolve_original_context_for_authenticated_owner() {
        String ref = issue("A", context);
        assertThat(store.resolve(ref, 8L)).isEmpty();
        var snapshot = store.resolve(ref, 7L).orElseThrow();
        assertThat(snapshot.context()).isEqualTo(context);
        assertThat(snapshot.route().targetOccurrenceId()).isEqualTo(14L);
        assertThat(snapshot.route().boardingOccurrenceId()).isEqualTo(12L);
        assertThat(store.resolve("unknown-reference", 7L)).isEmpty();
    }

    private String issue(String number, Context ownerContext) {
        return store.issue(ownerContext, route, BoardingCandidateMapper.tago(tago(number, "s1", 10), NOW), null);
    }
}
