package com.stopbell.transit.service;

import static com.stopbell.transit.service.BoardingCandidateTestFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.client.*;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailItem;
import com.stopbell.transit.mapper.BoardingCandidateMapper;
import com.stopbell.transit.service.BoardingCandidateReferenceStore.Context;
import com.stopbell.transit.service.BoardingCandidateReferenceStore.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoardingCandidateRevalidationTest {
    private final BoardingCandidateMetadataService metadata = mock(BoardingCandidateMetadataService.class);
    private final TagoVehicleLocationClient tago = mock(TagoVehicleLocationClient.class);
    private final SeoulBusVehicleLocationClient seoul = mock(SeoulBusVehicleLocationClient.class);
    private final SeoulBusVehicleDetailClient details = mock(SeoulBusVehicleDetailClient.class);
    private final MutableClock clock = new MutableClock();
    private final BoardingCandidateReferenceStore references = new BoardingCandidateReferenceStore(clock);
    private final BoardingCandidateService service = new BoardingCandidateService(metadata, tago, seoul, details, references, clock);
    private BoardingRouteSnapshot route;
    private Snapshot original;

    @BeforeEach
    void set_up() {
        route = route(TransitProvider.SEOUL_BUS, 12L);
        var context = new Context(7L, 1L, 14L, 12L);
        String reference = references.issue(context, route,
                BoardingCandidateMapper.roster(roster("v1", "서울70사1234", DATA_TIME), NOW),
                BoardingCandidateMapper.detail(detail("v1", "서울70사1234", "s1", 10, DATA_TIME), NOW));
        original = references.resolve(reference, 7L).orElseThrow();
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(roster("v1", "서울70사1234", DATA_TIME)));
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", "s2", 20, DATA_TIME)));
    }

    @Test
    @DisplayName("정상 후보 순위 변경과 같은 차량의 단조 진행은 선택 차량을 변경하지 않고 재검증한다")
    void allow_rank_change_and_forward_progress() {
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                roster("v2", "다른 번호", DATA_TIME), roster("v1", "서울70사1234", DATA_TIME)));
        var validated = service.revalidateSelected(original, route, "서울70사1234");
        assertThat(validated.vehicle().id()).isEqualTo("v1");
        assertThat(validated.progressIndex()).isEqualTo(1);
        verify(details).fetchVehicleDetail("v1");
        verifyNoInteractions(tago);
        verify(details, never()).fetchVehicleDetail("v2");
    }

    @Test
    @DisplayName("표시 이름과 노선번호만의 변경은 구조 변경으로 거부하지 않는다")
    void allow_display_only_changes() {
        var renamed = new BoardingRouteSnapshot(1L, route.provider(), route.externalRouteId(), null, 14L, 12L,
                route.traversal().stream().map(item -> new BoardingRouteSnapshot.Occurrence(item.id(), item.externalStopId(),
                        item.order(), "새 표시 이름")).toList(), "새 노선번호");
        assertThat(service.revalidateSelected(original, renamed, "서울70사1234").progressIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("차량 detail의 요청 ID 또는 전체 번호가 roster와 다르면 거부한다")
    void reject_detail_identity_mismatch() {
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v2", "서울70사1234", "s2", 20, DATA_TIME)));
        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "다른 번호", "s2", 20, DATA_TIME)));
        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
    }

    @Test
    @DisplayName("목적지 현재와 이후 관측은 다음 lap을 가정하지 않고 시작을 거부한다")
    void reject_target_not_ahead() {
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", "s4", 40, DATA_TIME)));
        expect(ErrorCode.BOARDING_TARGET_NOT_AHEAD);
    }

    @Test
    @DisplayName("서울 이동 중 의미와 order-only 및 ID/order 모순은 검증된 진행으로 취급하지 않는다")
    void reject_unverified_progress() {
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(new SeoulBusVehicleDetailItem(
                "v1", "서울70사1234", "s2", 20, 0, DATA_TIME, null, null)));
        expect(ErrorCode.BOARDING_TRACKING_UNSUPPORTED);
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", null, 20, DATA_TIME)));
        expect(ErrorCode.BOARDING_TRACKING_UNSUPPORTED);
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", "wrong-id", 20, DATA_TIME)));
        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
    }

    @Test
    @DisplayName("회차 문맥이 모호한 동일 Stop 재방문 traversal은 자동으로 확정하지 않는다")
    void reject_repeated_stop_traversal() {
        var repeated = new BoardingRouteSnapshot(1L, route.provider(), route.externalRouteId(), null, 14L, 12L,
                List.of(new BoardingRouteSnapshot.Occurrence(11L, "s1", 10, "처음"),
                        new BoardingRouteSnapshot.Occurrence(12L, "s2", 20, "승차"),
                        new BoardingRouteSnapshot.Occurrence(13L, "s1", 30, "재방문"),
                        new BoardingRouteSnapshot.Occurrence(14L, "s4", 40, "목적지")), "7000");
        original = new Snapshot(original.context(), repeated, repeated.fingerprint(), original.rosterVehicle(),
                original.detailVehicle(), original.expiresAt());
        route = repeated;
        expect(ErrorCode.BOARDING_TRACKING_UNSUPPORTED);
    }

    @Test
    @DisplayName("진행과 Provider 시각 역행 및 미래 또는 stale 응답은 거부한다")
    void reject_regression_and_invalid_time() {
        original = new Snapshot(original.context(), route, route.fingerprint(), original.rosterVehicle(),
                BoardingCandidateMapper.detail(detail("v1", "서울70사1234", "s3", 30, DATA_TIME), NOW), original.expiresAt());
        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
        original = new Snapshot(original.context(), route, route.fingerprint(), original.rosterVehicle(),
                BoardingCandidateMapper.detail(detail("v1", "서울70사1234", "s1", 10, DATA_TIME), NOW), original.expiresAt());
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", "s2", 20, "20261019115959")));
        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", "s2", 20, "20261019120001")));
        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
        clock.now = NOW.plusSeconds(61);
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", "s2", 20, DATA_TIME)));
        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
    }

    @Test
    @DisplayName("roster 또는 detail 실패와 잘못된 detail envelope는 정상 empty로 바꾸지 않는다")
    void reject_provider_failure() {
        when(seoul.fetchVehicleLocations(any())).thenThrow(TransitProviderClientException.http(TransitProvider.SEOUL_BUS, "roster", 500, null));
        expect(ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE);
        doReturn(rosterResponse(roster("v1", "서울70사1234", DATA_TIME))).when(seoul).fetchVehicleLocations(any());
        when(details.fetchVehicleDetail("v1")).thenThrow(TransitProviderClientException.protocol(TransitProvider.SEOUL_BUS, "detail", null));
        expect(ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE);
    }

    @Test
    @DisplayName("TAGO는 유효한 번호 후보여도 정확한 하차 추적 시작을 지원하지 않는다")
    void tago_remains_unsupported() {
        route = route(TransitProvider.TAGO, 12L);
        var vehicle = BoardingCandidateMapper.tago(tago("서울70사1234", "s1", 10), NOW);
        original = new Snapshot(new Context(7L, 1L, 14L, 12L), route, route.fingerprint(), vehicle, null, NOW.plusSeconds(60));
        expect(ErrorCode.BOARDING_TRACKING_UNSUPPORTED);
        verifyNoInteractions(tago, seoul, details);
    }

    @Test
    @DisplayName("차량 ID와 전체 번호의 공백 변경은 같은 identity로 합치지 않는다")
    void preserve_opaque_identity_whitespace() {
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(roster("v1 ", "서울70사1234", DATA_TIME)));
        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(roster("v1", "서울70사1234 ", DATA_TIME)));
        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
    }

    private void expect(ErrorCode code) {
        assertThatThrownBy(() -> service.revalidateSelected(original, route, "서울70사1234"))
                .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
