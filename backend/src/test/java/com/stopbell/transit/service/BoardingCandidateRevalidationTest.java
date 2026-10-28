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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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
        clock.now = NOW.plusSeconds(1);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(roster("v1", "서울70사1234", "20261019120001")));
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", "s2", 20, "20261019120001")));
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
    @DisplayName("같은 제공시각의 다른 occurrence는 시작 재검증에서도 새 진행으로 인정하지 않는다")
    void reject_progress_change_with_same_provider_time() {
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", "s2", 20, DATA_TIME)));
        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
    }

    @Test
    @DisplayName("같은 제공시각과 같은 occurrence의 반복은 재검증 입력으로만 유지한다")
    void allow_unchanged_source_without_inventing_progress() {
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", "s1", 10, DATA_TIME)));
        assertThat(service.revalidateSelected(original, route, "서울70사1234").progressIndex()).isZero();
    }

    @Test
    @DisplayName("이전 후보가 이동 중이어도 최신 정차 detail과 식별·시각·단조 진행을 검증하면 시작한다")
    void allow_previous_moving_reference_with_latest_arrival() {
        original = new Snapshot(original.context(), route, route.fingerprint(), original.rosterVehicle(),
                BoardingCandidateMapper.detail(new SeoulBusVehicleDetailItem("v1", "서울70사1234", "s1", 10,
                        0, DATA_TIME, null, null), NOW), original.expiresAt());
        assertThat(service.revalidateSelected(original, route, "서울70사1234").progressIndex()).isEqualTo(1);
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(new SeoulBusVehicleDetailItem(
                "v1", "서울70사1234", "s2", 20, 0, "20261019120001", null, null)));
        assertThat(service.revalidateSelected(original, route, "서울70사1234").baselineVerified()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("검증된 정차 기준점이 없는 이동 응답은 선택을 접수하되 진행 미검증으로 반환한다")
    void diagnose_latest_moving_detail(int previousFlag, CapturedOutput output) {
        original = new Snapshot(original.context(), route, route.fingerprint(), original.rosterVehicle(),
                BoardingCandidateMapper.detail(new SeoulBusVehicleDetailItem("v1", "서울70사1234", "s1", 10,
                        previousFlag, DATA_TIME, null, null), NOW), original.expiresAt());
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(new SeoulBusVehicleDetailItem(
                "v1", "서울70사1234", "s2", 20, 0, "20261019120001", null, null)));

        var selected = service.revalidateSelected(original, route, "서울70사1234");
        assertThat(selected.baselineVerified()).isFalse();
        assertThat(selected.progressIndex()).isEqualTo(1);
        verify(seoul, times(1)).fetchVehicleLocations(any());
        verify(details, times(1)).fetchVehicleDetail("v1");
        verifyNoInteractions(tago);
    }

    @ParameterizedTest
    @ValueSource(strings = {DATA_TIME, "20261019120001"})
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("정차 뒤 같은 occurrence의 이동 응답을 확인하면 최초 정차 근거와 원래 두 시각으로 시작한다")
    void reuse_stopped_baseline_without_refreshing_times(String latestTime, CapturedOutput output) {
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(new SeoulBusVehicleDetailItem(
                "v1", "서울70사1234", "s1", 10, 0, latestTime, null, null)));

        var verified = service.revalidateSelected(original, route, "서울70사1234");

        assertThat(verified.vehicle()).isSameAs(original.detailVehicle());
        assertThat(verified.vehicle().observedAt()).isEqualTo(NOW);
        assertThat(verified.vehicle().providerDataTime()).isEqualTo(NOW);
        assertThat(verified.progressIndex()).isZero();
        assertThat(output.getAll()).contains("outcome=STOPPED_BASELINE_REUSED")
                .doesNotContain("서울70사1234", "v1", "s1", "external-route");
        verify(seoul, times(1)).fetchVehicleLocations(any());
        verify(details, times(1)).fetchVehicleDetail("v1");
        verifyNoInteractions(tago);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ID", "NUMBER", "SOURCE_REGRESSION", "STALE_DETAIL", "STALE_BASELINE", "COARSE_CONFLICT"})
    @DisplayName("같은 occurrence의 이동 응답도 식별·시각·원래 기준점 freshness·운행 문맥 검증을 통과해야 한다")
    void reject_unsafe_moving_start(String issue) {
        String id = issue.equals("ID") ? "other-vehicle" : "v1";
        String number = issue.equals("NUMBER") ? "다른 번호" : "서울70사1234";
        String time = issue.equals("SOURCE_REGRESSION") ? "20261019115959" : "20261019120001";
        if (issue.equals("STALE_DETAIL")) {
            time = "20261019115859";
        } else if (issue.equals("STALE_BASELINE")) {
            original = new Snapshot(original.context(), route, route.fingerprint(), original.rosterVehicle(),
                    BoardingCandidateMapper.detail(detail("v1", number, "s1", 10, "20261019115859"), NOW),
                    original.expiresAt());
        } else if (issue.equals("COARSE_CONFLICT")) {
            when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                    new com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem("v1", number, 999,
                            "changed-section", 0, DATA_TIME, null, null, "not-an-occurrence", "1", null)));
        }
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(new SeoulBusVehicleDetailItem(
                id, number, "s1", 10, 0, time, null, null)));

        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LATEST_STOP_FLAG_UNVERIFIED", "ORIGINAL_DETAIL_MISSING",
            "ORIGINAL_STOP_FLAG_UNVERIFIED", "ORIGINAL_POSITION_UNVERIFIED", "CURRENT_POSITION_MISSING",
            "REPEATED_STOP_TRAVERSAL", "ORIGINAL_RUN_ENDED", "CURRENT_RUN_ENDED", "PROVIDER_UNVERIFIED"})
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("같은 미지원 오류의 서로 다른 차단 조건을 고정 사유로 구분하고 차량·정류장·reference를 기록하지 않는다")
    void diagnose_other_unsupported_conditions(String reason, CapturedOutput output) {
        var previous = original.detailVehicle();
        switch (reason) {
            case "LATEST_STOP_FLAG_UNVERIFIED" -> when(details.fetchVehicleDetail("v1"))
                    .thenReturn(detailResponse(new SeoulBusVehicleDetailItem("v1", "서울70사1234", "s2", 20,
                            null, "20261019120001", null, null)));
            case "ORIGINAL_DETAIL_MISSING" -> previous = null;
            case "ORIGINAL_STOP_FLAG_UNVERIFIED" -> previous = BoardingCandidateMapper.detail(
                    new SeoulBusVehicleDetailItem("v1", "서울70사1234", "s1", 10, null, DATA_TIME, null, null), NOW);
            case "ORIGINAL_POSITION_UNVERIFIED" -> previous = BoardingCandidateMapper.detail(
                    detail("v1", "서울70사1234", null, 10, DATA_TIME), NOW);
            case "CURRENT_POSITION_MISSING" -> when(details.fetchVehicleDetail("v1"))
                    .thenReturn(detailResponse(detail("v1", "서울70사1234", null, 20, "20261019120001")));
            case "REPEATED_STOP_TRAVERSAL" -> route = new BoardingRouteSnapshot(1L, route.provider(),
                    route.externalRouteId(), null, 14L, 12L, List.of(route.traversal().get(0),
                    route.traversal().get(1), new BoardingRouteSnapshot.Occurrence(13L, "s1", 30, "재방문"),
                    route.traversal().get(3)), route.routeNumber());
            case "ORIGINAL_RUN_ENDED", "CURRENT_RUN_ENDED" -> {
                var ended = roster("v1", "서울70사1234", DATA_TIME);
                ended = new com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem(ended.vehId(), ended.plainNo(),
                        999, "coarse", 1, DATA_TIME, null, null, "not-an-occurrence", "0", null);
                if (reason.equals("ORIGINAL_RUN_ENDED")) {
                    original = new Snapshot(original.context(), route, route.fingerprint(),
                            BoardingCandidateMapper.roster(ended, NOW), previous, original.expiresAt());
                } else {
                    when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(ended));
                }
            }
            case "PROVIDER_UNVERIFIED" -> route = route(TransitProvider.TAGO, 12L);
            default -> throw new AssertionError(reason);
        }
        original = new Snapshot(original.context(), route, route.fingerprint(), original.rosterVehicle(),
                previous, original.expiresAt());

        if (reason.endsWith("RUN_ENDED") || reason.equals("PROVIDER_UNVERIFIED")) {
            expect(ErrorCode.BOARDING_TRACKING_UNSUPPORTED);
            assertDiagnostic(output, reason);
        } else {
            var selected = service.revalidateSelected(original, route, "서울70사1234");
            assertThat(selected.baselineVerified()).isEqualTo(!reason.equals("LATEST_STOP_FLAG_UNVERIFIED")
                    && !reason.equals("REPEATED_STOP_TRAVERSAL") && !reason.equals("CURRENT_POSITION_MISSING"));
        }
    }

    private void assertDiagnostic(CapturedOutput output, String reason) {
        assertThat(output.getAll()).contains("stage=candidate-revalidation provider=" + route.provider(),
                        "reason=" + reason + " responseStatus=409")
                .doesNotContain("서울70사1234", "v1", "s1", "s2", "external-route", DATA_TIME,
                        original.metadataFingerprint(), original.expiresAt().toString());
    }

    @Test
    @DisplayName("같은 제공시각에 roster 구간 문맥이 달라지면 sectOrd를 정류장 진행으로 해석하지 않고 거부한다")
    void reject_conflicting_roster_at_same_provider_time() {
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                new com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem("v1", "서울70사1234", 1000,
                        "different-section", 1, DATA_TIME, null, null, "different-next-stop", "1", null)));
        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("원래 후보나 최신 roster의 명시적 운행 종료는 같은 ID의 운행 재연결 근거로 사용하지 않는다")
    void reject_known_run_end(boolean originalEnded) {
        var ended = new com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem("v1", "서울70사1234", 999,
                "coarse", 1, DATA_TIME, null, null, "not-an-occurrence", "0", null);
        if (originalEnded) {
            original = new Snapshot(original.context(), route, route.fingerprint(),
                    BoardingCandidateMapper.roster(ended, NOW), original.detailVehicle(), original.expiresAt());
        } else {
            when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(ended));
        }
        expect(ErrorCode.BOARDING_TRACKING_UNSUPPORTED);
        verifyNoInteractions(details);
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
    @DisplayName("목적지 현재와 이후 관측도 차량 선택을 허용하되 검증된 시작 근거로 승격하지 않는다")
    void target_not_ahead_has_no_baseline() {
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", "s4", 40, "20261019120001")));
        assertThat(service.revalidateSelected(original, route, "서울70사1234").baselineVerified()).isFalse();
    }

    @Test
    @DisplayName("서울 이동 중 의미와 order-only 및 ID/order 모순은 검증된 진행으로 취급하지 않는다")
    void reject_unverified_progress() {
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(new SeoulBusVehicleDetailItem(
                "v1", "서울70사1234", "s2", 20, 0, DATA_TIME, null, null)));
        expect(ErrorCode.BOARDING_CANDIDATE_CHANGED);
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", null, 20, DATA_TIME)));
        assertThat(service.revalidateSelected(original, route, "서울70사1234").baselineVerified()).isFalse();
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
        assertThat(service.revalidateSelected(original, route, "서울70사1234").baselineVerified()).isFalse();
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
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse(detail("v1", "서울70사1234", "s2", 20, "20261019120002")));
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
    @DisplayName("정상 empty detail에서는 원래 위치와 시각을 미검증으로만 보존하고 식별은 최신 roster와 비교한다")
    void empty_detail_preserves_unverified_original_reference() {
        when(details.fetchVehicleDetail("v1")).thenReturn(detailResponse());
        var validated = service.revalidateSelected(original, route, "서울70사1234");
        assertThat(validated.baselineVerified()).isFalse();
        assertThat(validated.vehicle()).isEqualTo(original.detailVehicle());
        assertThat(validated.vehicle().observedAt()).isEqualTo(NOW);
        assertThat(validated.progressIndex()).isZero();
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
