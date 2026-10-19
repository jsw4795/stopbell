package com.stopbell.boarding.service;

import static com.stopbell.boarding.BoardingSessionFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;

import com.stopbell.boarding.domain.*;
import com.stopbell.boarding.domain.BoardingProgressObservation.Meaning;
import com.stopbell.boarding.entity.*;
import com.stopbell.transit.client.*;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.seoul.*;
import com.stopbell.transit.dto.tago.*;
import com.stopbell.transit.service.VehiclePollingCycle;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardingVehicleTrackerTest {
    private final Clock clock = Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC);
    private final TagoVehicleLocationClient tago = mock(TagoVehicleLocationClient.class);
    private final SeoulBusVehicleLocationClient seoul = mock(SeoulBusVehicleLocationClient.class);
    private final SeoulBusVehicleDetailClient detail = mock(SeoulBusVehicleDetailClient.class);
    private final BoardingVehicleTracker tracker = new BoardingVehicleTracker(clock);
    private final BoardingTrackingSnapshot session = BoardingProgressEvaluatorTest.snapshot(0);

    @BeforeEach
    void set_up() {
        when(seoul.fetchVehicleLocations(any())).thenReturn(roster(rosterItem("vehicle-id", NUMBER, "20261020120019")));
        when(detail.fetchVehicleDetail(any())).thenReturn(detail("vehicle-id", NUMBER, "s1", 20, 1, "20261020120019"));
    }

    @Test
    @DisplayName("서울은 요청 차량 ID와 전체 번호 및 최신 roster/detail을 함께 보존하며 같은 cycle의 조회를 공유한다")
    void share_raw_responses() {
        var cycle = cycle();
        var received = cycle.roster("route-id");
        var input = tracker.observe(session, cycle);
        tracker.observe(session, cycle);
        assertThat(input.reason()).isNull();
        assertThat(input.observation().vehicle().id()).isEqualTo("vehicle-id");
        assertThat(input.observation().vehicle().number()).isEqualTo(NUMBER);
        assertThat(input.observation().vehicle().observedAt()).isEqualTo(received.observedAt());
        assertThat(input.observation().meaning()).isEqualTo(Meaning.DIRECT_ARRIVAL);
        verify(seoul, times(1)).fetchVehicleLocations(any());
        verify(detail, times(1)).fetchVehicleDetail("vehicle-id");
    }

    @ParameterizedTest
    @CsvSource({"0,VEHICLE_MISSING", "1,VEHICLE_MISSING", "2,IDENTITY_CONFLICT", "3,IDENTITY_CONFLICT", "4,IDENTITY_CONFLICT"})
    @DisplayName("정상 empty와 선택 차량 소실은 구분 가능한 소실 근거이며 번호·ID·중복 충돌 시 다른 차량으로 대체하지 않는다")
    void roster_identity(int scenario, BoardingStateReason expected) {
        var selected = rosterItem("vehicle-id", NUMBER, "20261020120019");
        var response = switch (scenario) {
            case 0 -> new SeoulBusVehicleLocationResponse(new SeoulBusVehicleLocationResponse.Header("4", null), null);
            case 1 -> roster(rosterItem("other-id", "다른차량5678", "20261020120019"));
            case 2 -> roster(rosterItem("vehicle-id", "변경번호1234", "20261020120019"));
            case 3 -> roster(selected, selected);
            default -> roster(selected, rosterItem("other-id", NUMBER, "20261020120019"));
        };
        when(seoul.fetchVehicleLocations(any())).thenReturn(response);
        assertThat(tracker.observe(session, cycle()).reason()).isEqualTo(expected);
        verifyNoInteractions(detail);
    }

    @Test
    @DisplayName("목록에 식별 불가 항목이 있으면 확정 차량의 부재를 정상 소실로 단정하지 않는다")
    void incomplete_roster_is_not_missing_evidence() {
        when(seoul.fetchVehicleLocations(any())).thenReturn(roster(rosterItem(null, "unknown", "20261020120019")));
        assertThat(tracker.observe(session, cycle()).reason()).isEqualTo(BoardingStateReason.IDENTITY_CONFLICT);
        verifyNoInteractions(detail);
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-id", "other-number"})
    @DisplayName("서울 detail의 요청 ID 또는 roster 전체 번호 불일치는 식별 충돌로 보류한다")
    void detail_identity(String changed) {
        when(detail.fetchVehicleDetail(any())).thenReturn(detail(changed.equals("other-id") ? changed : "vehicle-id",
                changed.equals("other-number") ? changed : NUMBER, "s1", 20, 1, "20261020120019"));
        assertThat(tracker.observe(session, cycle()).reason()).isEqualTo(BoardingStateReason.IDENTITY_CONFLICT);
    }

    @Test
    @DisplayName("정상 empty detail은 roster 차량 소실을 뜻하지 않는다")
    void empty_detail() {
        when(detail.fetchVehicleDetail(any())).thenReturn(new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("4", null), null));
        assertThat(tracker.observe(session, cycle()).reason()).isEqualTo(BoardingStateReason.STALE_PROGRESS);
    }

    @Test
    @DisplayName("서울 detail에 동일 차량이 중복되면 위치를 임의 선택하지 않는다")
    void duplicate_detail() {
        var item = detail("vehicle-id", NUMBER, "s1", 20, 1, "20261020120019").body().itemList().getFirst();
        when(detail.fetchVehicleDetail(any())).thenReturn(new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("0", null), new SeoulBusVehicleDetailResponse.Body(List.of(item, item))));
        assertThat(tracker.observe(session, cycle()).reason()).isEqualTo(BoardingStateReason.IDENTITY_CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    @DisplayName("실제 서울 이동 중 detail 의미가 검증되지 않은 범위는 테스트 fixture의 검증된 진행과 구분한다")
    void moving_semantics_remain_unverified(int flag) {
        when(detail.fetchVehicleDetail(any())).thenReturn(detail("vehicle-id", NUMBER, "s1", 20, flag, "20261020120019"));
        assertThat(tracker.observe(session, cycle()).observation().meaning()).isEqualTo(Meaning.UNVERIFIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"20261020115800", "20261020120021", "bad-time", ""})
    @DisplayName("roster stale·future·잘못된 시각은 detail 호출 전 보류한다")
    void roster_time(String time) {
        when(seoul.fetchVehicleLocations(any())).thenReturn(roster(rosterItem("vehicle-id", NUMBER, time)));
        assertThat(tracker.observe(session, cycle()).reason()).isEqualTo(BoardingStateReason.STALE_PROGRESS);
        verifyNoInteractions(detail);
    }

    @Test
    @DisplayName("detail I/O 후에는 roster와 detail의 시각을 다시 검증한다")
    void roster_ages_during_detail() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(NOW.plusSeconds(20));
        var advancing = mock(Clock.class);
        when(advancing.instant()).thenAnswer(invocation -> now.get());
        when(detail.fetchVehicleDetail(any())).thenAnswer(invocation -> {
            now.set(NOW.plusSeconds(100));
            return detail("vehicle-id", NUMBER, "s1", 20, 1, "20261020120139");
        });
        var input = new BoardingVehicleTracker(advancing).observe(session, new VehiclePollingCycle(tago, seoul, detail, advancing));
        assertThat(input.reason()).isEqualTo(BoardingStateReason.STALE_PROGRESS);
    }

    @Test
    @DisplayName("Provider 실패도 cycle 안에서 공유하며 다음 cycle에서만 다시 요청한다")
    void failure_is_shared_not_amplified() {
        var failure = TransitProviderClientException.protocol(TransitProvider.SEOUL_BUS, "roster", null);
        when(seoul.fetchVehicleLocations(any())).thenThrow(failure);
        var cycle = cycle();
        assertThatThrownBy(() -> tracker.observe(session, cycle)).isSameAs(failure);
        assertThatThrownBy(() -> tracker.observe(session, cycle)).isSameAs(failure);
        verify(seoul, times(1)).fetchVehicleLocations(any());
        assertThatThrownBy(() -> tracker.observe(session, cycle())).isSameAs(failure);
        verify(seoul, times(2)).fetchVehicleLocations(any());
        verifyNoInteractions(detail);
    }

    @Test
    @DisplayName("Route와 TAGO cityCode는 공유 key를 구분하고 서울 detail은 Route와 관계없이 고유 차량 ID로 공유한다")
    void request_context_keys() {
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(null));
        var cycle = cycle();
        cycle.tago("route", "31010");
        cycle.tago("route", "31010");
        cycle.tago("route", "32010");
        cycle.tago("other", "31010");
        cycle.roster("route");
        cycle.roster("other");
        cycle.detail("vehicle-id");
        cycle.detail("vehicle-id");
        cycle.detail("other-id");
        verify(tago, times(3)).fetchVehicleLocations(any());
        verify(seoul, times(2)).fetchVehicleLocations(any());
        verify(detail, times(2)).fetchVehicleDetail(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("TAGO nodeid 누락은 정차 위치를 합성하지 않으며 실제 하차 지원은 계속 보류한다")
    void tago_support_remains_unverified(boolean hasNodeId) {
        var traversal = new BoardingTraversalSnapshot(1, TransitProvider.TAGO, "route-id", "31010", session.traversal().occurrences(),
                3, BoardingTraversalSnapshot.AlightAvailability.ALLOWED, "controlled-test-evidence");
        var checkpoint = new BoardingCheckpoint("s0", 10, 0, BoardingCheckpoint.Evidence.ID_ORDER, null, UTC_NOW, null);
        var tagoSession = new BoardingTrackingSnapshot(1L, 0, BoardingSessionStatus.TRACKING, null, TransitProvider.TAGO,
                "route-id", "31010", NUMBER, NUMBER, traversal, checkpoint, UTC_NOW.plusHours(4));
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(hasNodeId ? "s1" : null));
        var input = tracker.observe(tagoSession, cycle());
        assertThat(input.observation().meaning()).isEqualTo(Meaning.UNVERIFIED);
        assertThat(input.observation().orderOnlyVerified()).isFalse();
        assertThat(input.observation().vehicle().stopId()).isEqualTo(hasNodeId ? "s1" : null);
        assertThat(input.observation().vehicle().providerDataTime()).isNull();
        verifyNoInteractions(seoul, detail);
    }

    private VehiclePollingCycle cycle() { return new VehiclePollingCycle(tago, seoul, detail, clock); }
    private static SeoulBusVehicleLocationItem rosterItem(String id, String number, String time) {
        return new SeoulBusVehicleLocationItem(id, number, 1, "section", 1, time, null, null, null, null, null);
    }
    private static SeoulBusVehicleLocationResponse roster(SeoulBusVehicleLocationItem... items) {
        return new SeoulBusVehicleLocationResponse(new SeoulBusVehicleLocationResponse.Header("0", null),
                new SeoulBusVehicleLocationResponse.Body(List.of(items)));
    }
    private static SeoulBusVehicleDetailResponse detail(String id, String number, String stop, int order, int flag, String time) {
        return new SeoulBusVehicleDetailResponse(new SeoulBusVehicleDetailResponse.Header("0", null),
                new SeoulBusVehicleDetailResponse.Body(List.of(new SeoulBusVehicleDetailItem(id, number, stop, order, flag, time, null, null))));
    }
    private static TagoVehicleLocationResponse tagoResponse(String stopId) {
        return new TagoVehicleLocationResponse(new TagoVehicleLocationResponse.Response(
                new TagoVehicleLocationResponse.Header("00", null), new TagoVehicleLocationResponse.Body(
                        new TagoVehicleLocationResponse.Items(List.of(new TagoVehicleLocationItem(NUMBER, stopId, 20, null, null))), 1, 1, 1000)));
    }
}
