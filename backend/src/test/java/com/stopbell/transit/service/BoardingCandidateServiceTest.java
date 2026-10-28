package com.stopbell.transit.service;

import static com.stopbell.transit.service.BoardingCandidateTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.client.SeoulBusVehicleDetailClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.TagoVehicleLocationClient;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.BoardingCandidatesResponse;
import com.stopbell.transit.dto.BoardingCandidatesResponse.Candidate;
import com.stopbell.transit.dto.BoardingCandidatesResponse.CandidateSetStatus;
import com.stopbell.transit.dto.BoardingCandidatesResponse.ConfirmationStatus;
import com.stopbell.transit.dto.BoardingCandidatesResponse.ProgressKind;
import com.stopbell.transit.dto.BoardingCandidatesResponse.ReasonCode;
import com.stopbell.transit.dto.BoardingCandidatesResponse.TrackingSupport;
import com.stopbell.transit.service.BoardingCandidateReferenceStore.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoardingCandidateServiceTest {
    private final BoardingCandidateMetadataService metadata = mock(BoardingCandidateMetadataService.class);
    private final TagoVehicleLocationClient tago = mock(TagoVehicleLocationClient.class);
    private final SeoulBusVehicleLocationClient seoul = mock(SeoulBusVehicleLocationClient.class);
    private final SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
    private final MutableClock clock = new MutableClock();
    private final BoardingCandidateReferenceStore references = new BoardingCandidateReferenceStore(clock);
    private final BoardingCandidateService service = new BoardingCandidateService(
            metadata, tago, seoul, detailClient, references, clock);

    @BeforeEach
    void set_up() {
        useRoute(TransitProvider.TAGO, 12L);
    }

    @Test
    @DisplayName("TAGO 정상 후보는 원본 시각 없이 번호 확인과 진행 간격을 제공하고 하차 추적은 보류한다")
    void tago_number_checking_without_provider_time() {
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("경기70바5770", "s3", 30)));
        var response = find(12L);
        assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.AVAILABLE);
        assertThat(response.providerDataTime()).isNull();
        Candidate candidate = response.candidates().getFirst();
        assertThat(candidate.vehicleNumber()).isEqualTo("경기70바5770");
        assertThat(candidate.confirmationStatus()).isEqualTo(ConfirmationStatus.AVAILABLE);
        assertThat(candidate.candidateRef()).isNotBlank();
        assertThat(candidate.boardingOccurrenceGap()).isEqualTo(-1);
        assertThat(candidate.progress().kind()).isEqualTo(ProgressKind.ROUTE_PROGRESS);
        assertThat(candidate.trackingSupport()).isEqualTo(TrackingSupport.HELD);
        assertThat(candidate.trackingReasonCode()).isEqualTo(ReasonCode.TAGO_ALIGHT_UNVERIFIED);
        var snapshot = references.resolve(candidate.candidateRef(), context(12L)).orElseThrow();
        assertThat(snapshot.route().provider()).isEqualTo(TransitProvider.TAGO);
        assertThat(snapshot.route().cityCode()).isEqualTo("31010");
        assertThat(snapshot.rosterVehicle().id()).isEqualTo("경기70바5770");
        verify(tago).fetchVehicleLocations(argThat(request -> request.externalRouteId().equals("external-route")
                && request.context().cityCode().equals("31010")));
        verifyNoInteractions(seoul, detailClient);
    }

    @Test
    @DisplayName("nodeid가 없어도 번호 후보를 유지하고 검증되지 않은 order로 위치를 합성하지 않는다")
    void missing_node_id_keeps_number_without_position() {
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("A", null, 30)));
        var candidate = find(12L).candidates().getFirst();
        assertThat(candidate.confirmationStatus()).isEqualTo(ConfirmationStatus.AVAILABLE);
        assertThat(candidate.candidateRef()).isNotNull();
        assertThat(candidate.progress()).isNull();
        assertThat(candidate.boardingOccurrenceGap()).isNull();
        assertThat(candidate.reasonCode()).isEqualTo(ReasonCode.POSITION_UNAVAILABLE);
    }

    @Test
    @DisplayName("위치 필드가 모두 없는 차량도 번호를 확인할 수 있다")
    void missing_position_keeps_candidate() {
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("A", null, null)));
        assertThat(find(12L).candidates().getFirst().vehicleNumber()).isEqualTo("A");
    }

    @Test
    @DisplayName("검증된 traversal 배열 index로 정렬하고 raw order 차이는 간격으로 사용하지 않는다")
    void sort_by_traversal_index() {
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(
                tago("Z", "s4", 40), tago("B", "s3", 30), tago("A", "s1", 10), tago("C", null, 20)));
        var candidates = find(12L).candidates();
        assertThat(candidates).extracting(Candidate::vehicleNumber).containsExactly("A", "B", "Z", "C");
        assertThat(candidates).extracting(Candidate::boardingOccurrenceGap).containsExactly(1, -1, -2, null);
        assertThat(candidates).allMatch(candidate -> candidate.confirmationStatus() == ConfirmationStatus.AVAILABLE);
    }

    @Test
    @DisplayName("승차 occurrence가 없어도 전체 번호 목록을 안정적으로 제공하며 간격은 계산하지 않는다")
    void no_boarding_occurrence_uses_number_order() {
        useRoute(TransitProvider.TAGO, null);
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("B", "s2", 20), tago("A", "s4", 40)));
        var candidates = find(null).candidates();
        assertThat(candidates).extracting(Candidate::vehicleNumber).containsExactly("A", "B");
        assertThat(candidates).allMatch(candidate -> candidate.boardingOccurrenceGap() == null);
    }

    @Test
    @DisplayName("정상 empty는 Provider 실패와 구분하며 참조나 후보를 만들지 않는다")
    void normal_empty() {
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse());
        var response = find(12L);
        assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.EMPTY);
        assertThat(response.candidates()).isEmpty();
    }

    @Test
    @DisplayName("TAGO 번호 누락 항목을 보류 항목으로 남겨 정상 단일 후보로 위장하지 않는다")
    void missing_tago_number_is_partial() {
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago(null, "s1", 10), tago("A", "s2", 20)));
        var response = find(12L);
        assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.PARTIAL);
        assertThat(response.candidates()).hasSize(2);
        assertHeld(response.candidates().getLast(), ReasonCode.VEHICLE_ID_MISSING);
    }

    @Test
    @DisplayName("동일 TAGO 번호의 상충하는 진행은 양쪽 모두 확정을 보류한다")
    void conflicting_tago_progress() {
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("A", "s1", 10), tago("A", "s2", 20)));
        assertThat(find(12L).candidates()).hasSize(2).allSatisfy(candidate ->
                assertHeld(candidate, ReasonCode.IDENTITY_CONFLICT));
    }

    @Test
    @DisplayName("동일한 중복 item은 한 후보로 병합하고 추가 detail을 만들지 않는다")
    void identical_duplicates_are_deduplicated() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        var item = roster("id1", "A", DATA_TIME);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(item, item));
        when(detailClient.fetchVehicleDetail("id1")).thenReturn(detailResponse(detail("id1", "A", "s2", 20, DATA_TIME)));
        assertThat(find(12L).candidates()).hasSize(1);
        verify(detailClient, times(1)).fetchVehicleDetail("id1");
    }

    @Test
    @DisplayName("metadata와 상충하는 Stop ID와 순번은 보류하고 가짜 occurrence를 생성하지 않는다")
    void metadata_mismatch_is_held() {
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("A", "s2", 30)));
        assertHeld(find(12L).candidates().getFirst(), ReasonCode.METADATA_MISMATCH);
    }

    @Test
    @DisplayName("전체 roster의 HTTP와 transport와 protocol과 Provider 실패는 모두 안전한 503으로 전달한다")
    void roster_failures_are_not_empty() {
        List<TransitProviderClientException> failures = List.of(
                TransitProviderClientException.http(TransitProvider.TAGO, "list", 500, null),
                TransitProviderClientException.transport(TransitProvider.TAGO, "list", null),
                TransitProviderClientException.protocol(TransitProvider.TAGO, "list", null),
                TransitProviderClientException.provider(TransitProvider.TAGO, "list", "secret-message"));
        for (var failure : failures) {
            doThrow(failure).when(tago).fetchVehicleLocations(any());
            assertThatThrownBy(() -> find(12L)).isInstanceOfSatisfying(ApiException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE));
        }
    }

    @Test
    @DisplayName("서울은 승차 occurrence 없이 roster 번호만 제공하고 coarse 위치를 정류장으로 변환하지 않는다")
    void seoul_roster_only_preserves_full_number() {
        useRoute(TransitProvider.SEOUL_BUS, null);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(roster("raw-id", "서울74사1234", DATA_TIME)));
        var candidate = find(null).candidates().getFirst();
        assertThat(candidate.vehicleNumber()).isEqualTo("서울74사1234");
        assertThat(candidate.progress()).isNull();
        assertThat(candidate.reasonCode()).isEqualTo(ReasonCode.DETAIL_NOT_REQUESTED);
        assertThat(candidate.trackingSupport()).isEqualTo(TrackingSupport.CONDITIONAL);
        assertThat(references.resolve(candidate.candidateRef(), context(null)).orElseThrow()
                .rosterVehicle().id()).isEqualTo("raw-id");
        verifyNoInteractions(detailClient);
    }

    @Test
    @DisplayName("서울의 검증된 detail만 진행과 간격을 표시하고 최신 재검증 조건부 지원으로 반환한다")
    void seoul_verified_detail() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(roster("id", "A", DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id")).thenReturn(detailResponse(detail("id", "A", "s3", 30, DATA_TIME)));
        var candidate = find(12L).candidates().getFirst();
        assertThat(candidate.progress().occurrenceId()).isEqualTo(13L);
        assertThat(candidate.progress().kind()).isEqualTo(ProgressKind.ARRIVAL_OBSERVED);
        assertThat(candidate.boardingOccurrenceGap()).isEqualTo(-1);
        assertThat(candidate.providerDataTime()).isEqualTo(NOW);
        assertThat(candidate.trackingSupport()).isEqualTo(TrackingSupport.CONDITIONAL);
        assertThat(candidate.trackingReasonCode()).isEqualTo(ReasonCode.REVALIDATION_REQUIRED);
        assertThat(references.resolve(candidate.candidateRef(), context(12L)).orElseThrow()
                .detailVehicle().number()).isEqualTo("A");
    }

    @Test
    @DisplayName("목적지 현재 또는 이후 후보는 등록을 허용하되 소급 알림 불가 제한을 표시한다")
    void target_not_ahead() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(roster("id", "A", DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id")).thenReturn(detailResponse(detail("id", "A", "s4", 40, DATA_TIME)));
        var candidate = find(12L).candidates().getFirst();
        assertThat(candidate.confirmationStatus()).isEqualTo(ConfirmationStatus.AVAILABLE);
        assertThat(candidate.trackingReasonCode()).isEqualTo(ReasonCode.REVALIDATION_REQUIRED);
        assertThat(candidate.trackingSupport()).isEqualTo(TrackingSupport.CONDITIONAL);
        assertThat(candidate.limitations()).contains(ReasonCode.TARGET_NOT_AHEAD);
    }

    @Test
    @DisplayName("서울 차량 ID 또는 번호가 누락되면 detail 조회 없이 보류한다")
    void seoul_missing_identity() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                roster(null, "A", DATA_TIME), roster("id2", " ", DATA_TIME)));
        var response = find(12L);
        assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.PARTIAL);
        assertHeld(response.candidates().getFirst(), ReasonCode.VEHICLE_ID_MISSING);
        assertHeld(response.candidates().getLast(), ReasonCode.VEHICLE_NUMBER_MISSING);
        verifyNoInteractions(detailClient);
    }

    @Test
    @DisplayName("서울 동일 ID의 다른 번호와 동일 번호의 다른 ID는 관련 후보를 모두 보류한다")
    void seoul_identity_collisions() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                roster("id1", "A", DATA_TIME), roster("id1", "B", DATA_TIME), roster("id2", "A", DATA_TIME)));
        assertThat(find(12L).candidates()).hasSize(3).allSatisfy(candidate ->
                assertHeld(candidate, ReasonCode.IDENTITY_CONFLICT));
        verifyNoInteractions(detailClient);
    }

    @Test
    @DisplayName("서울 요청 vehId와 detail 응답 ID 불일치는 임의로 연결하지 않는다")
    void seoul_detail_id_mismatch() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(roster("id", "A", DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id")).thenReturn(detailResponse(detail("other-id", "A", "s2", 20, DATA_TIME)));
        assertHeld(find(12L).candidates().getFirst(), ReasonCode.DETAIL_ID_MISMATCH);
    }

    @Test
    @DisplayName("서울 roster와 detail 전체 번호 불일치도 확정 가능한 후보로 반환하지 않는다")
    void seoul_detail_number_mismatch() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(roster("id", "A", DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id")).thenReturn(detailResponse(detail("id", "B", "s2", 20, DATA_TIME)));
        assertHeld(find(12L).candidates().getFirst(), ReasonCode.DETAIL_NUMBER_MISMATCH);
    }

    @Test
    @DisplayName("서울 일부 detail 실패는 정상 후보와 함께 PARTIAL로 제공하고 실패 후보는 보류한다")
    void seoul_partial_detail_failure() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                roster("id1", "A", DATA_TIME), roster("id2", "B", DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id1")).thenReturn(detailResponse(detail("id1", "A", "s2", 20, DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id2")).thenThrow(
                TransitProviderClientException.transport(TransitProvider.SEOUL_BUS, "detail", null));
        var response = find(12L);
        assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.PARTIAL);
        assertThat(response.candidates()).hasSize(2);
        assertThat(response.candidates().getFirst().confirmationStatus()).isEqualTo(ConfirmationStatus.AVAILABLE);
        assertHeld(response.candidates().getLast(), ReasonCode.DETAIL_TRANSPORT_FAILURE);
        verify(detailClient, times(2)).fetchVehicleDetail(any());
        verify(seoul, times(1)).fetchVehicleLocations(any());
    }

    @Test
    @DisplayName("서울 정상 empty detail은 차량 소실로 단정하지 않고 번호와 보류 사유를 보존한다")
    void seoul_empty_detail() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(roster("id", "A", DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id")).thenReturn(detailResponse());
        var candidate = find(12L).candidates().getFirst();
        assertThat(candidate.confirmationStatus()).isEqualTo(ConfirmationStatus.AVAILABLE);
        assertThat(candidate.trackingSupport()).isEqualTo(TrackingSupport.CONDITIONAL);
        assertThat(candidate.reasonCode()).isEqualTo(ReasonCode.DETAIL_EMPTY);
        assertThat(candidate.candidateRef()).isNotBlank();
        assertThat(candidate.progress()).isNull();
    }

    @Test
    @DisplayName("서울 stale 및 future roster는 detail 호출 없이 보류한다")
    void seoul_stale_and_future_roster() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                roster("id1", "A", "20261019115859"), roster("id2", "B", "20261019120001")));
        var candidates = find(12L).candidates();
        assertHeld(candidates.getFirst(), ReasonCode.STALE_OBSERVATION);
        assertHeld(candidates.getLast(), ReasonCode.FUTURE_OBSERVATION);
        verifyNoInteractions(detailClient);
    }

    @Test
    @DisplayName("서울 제공시각 누락과 불가능한 날짜는 정상 시각으로 보정하지 않고 보류한다")
    void invalid_provider_time_is_held() {
        useRoute(TransitProvider.SEOUL_BUS, null);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                roster("id1", "A", null), roster("id2", "B", "20260230120000")));
        var candidates = find(null).candidates();
        assertHeld(candidates.getFirst(), ReasonCode.PROVIDER_TIME_MISSING);
        assertHeld(candidates.getLast(), ReasonCode.PROVIDER_TIME_INVALID);
    }

    @Test
    @DisplayName("서울 stale 또는 future detail도 roster 성공으로 숨기지 않는다")
    void invalid_detail_time_is_held() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                roster("id1", "A", DATA_TIME), roster("id2", "B", DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id1")).thenReturn(detailResponse(detail("id1", "A", "s2", 20, "20261019115859")));
        when(detailClient.fetchVehicleDetail("id2")).thenReturn(detailResponse(detail("id2", "B", "s2", 20, "20261019120001")));
        var candidates = find(12L).candidates();
        assertHeld(candidates.getFirst(), ReasonCode.STALE_OBSERVATION);
        assertHeld(candidates.getLast(), ReasonCode.FUTURE_OBSERVATION);
    }

    @Test
    @DisplayName("느린 detail 조회 중 roster 관측이 만료되면 새 시각으로 갱신하지 않고 보류한다")
    void slow_detail_does_not_refresh_roster_observation() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(roster("id", "A", DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id")).thenAnswer(invocation -> {
            clock.now = NOW.plusSeconds(61);
            return detailResponse(detail("id", "A", "s2", 20, "20261019120101"));
        });
        var response = find(12L);
        assertThat(response.observedAt()).isEqualTo(NOW);
        assertHeld(response.candidates().getFirst(), ReasonCode.STALE_OBSERVATION);
    }

    @Test
    @DisplayName("같은 문맥 재조회는 이전 reference를 eviction하고 새로운 관측으로만 발급한다")
    void repeated_lookup_replaces_context() {
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("A", "s1", 10)));
        String original = find(12L).candidates().getFirst().candidateRef();
        clock.now = NOW.plus(Duration.ofSeconds(5));
        String next = find(12L).candidates().getFirst().candidateRef();
        assertThat(references.resolve(original, context(12L))).isEmpty();
        assertThat(references.resolve(next, context(12L)).orElseThrow().rosterVehicle().observedAt())
                .isEqualTo(clock.now);
    }

    @Test
    @DisplayName("Provider GPS가 달라도 번호와 진행 및 추천 간격은 바뀌지 않는다")
    void gps_is_not_used_as_evidence() {
        var first = new com.stopbell.transit.dto.tago.TagoVehicleLocationItem("A", "s2", 20,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
        var second = new com.stopbell.transit.dto.tago.TagoVehicleLocationItem("A", "s2", 20,
                java.math.BigDecimal.valueOf(90), java.math.BigDecimal.valueOf(180));
        when(tago.fetchVehicleLocations(any())).thenReturn(tagoResponse(first, second));
        var candidates = find(12L).candidates();
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().boardingOccurrenceGap()).isZero();
        assertThat(candidates.getFirst().confirmationStatus()).isEqualTo(ConfirmationStatus.AVAILABLE);
    }

    @Test
    @DisplayName("서울 정상 roster empty는 detail 호출 없이 EMPTY로 응답한다")
    void seoul_normal_empty() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse());
        assertThat(find(12L).candidateSetStatus()).isEqualTo(CandidateSetStatus.EMPTY);
        verifyNoInteractions(detailClient);
    }

    @Test
    @DisplayName("서울 detail의 HTTP와 Provider와 protocol 실패 범위를 개별 사유로 보존한다")
    void detail_failure_kinds_are_preserved() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                roster("id1", "A", DATA_TIME), roster("id2", "B", DATA_TIME), roster("id3", "C", DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id1")).thenThrow(
                TransitProviderClientException.http(TransitProvider.SEOUL_BUS, "detail", 503, null));
        when(detailClient.fetchVehicleDetail("id2")).thenThrow(
                TransitProviderClientException.provider(TransitProvider.SEOUL_BUS, "detail", "99"));
        when(detailClient.fetchVehicleDetail("id3")).thenThrow(
                TransitProviderClientException.protocol(TransitProvider.SEOUL_BUS, "detail", null));
        var candidates = find(12L).candidates();
        assertHeld(candidates.get(0), ReasonCode.DETAIL_HTTP_FAILURE);
        assertHeld(candidates.get(1), ReasonCode.DETAIL_PROVIDER_FAILURE);
        assertHeld(candidates.get(2), ReasonCode.DETAIL_PROTOCOL_FAILURE);
    }

    @Test
    @DisplayName("서울 이동 중과 직접 flag 미확인 진행은 정차 도착으로 표시하지 않는다")
    void moving_and_unknown_flag_are_not_arrival() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                roster("id1", "A", DATA_TIME), roster("id2", "B", DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id1")).thenReturn(detailResponse(
                new com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailItem("id1", "A", "s2", 20, 0, DATA_TIME, null, null)));
        when(detailClient.fetchVehicleDetail("id2")).thenReturn(detailResponse(
                new com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailItem("id2", "B", "s2", 20, null, DATA_TIME, null, null)));
        var candidates = find(12L).candidates();
        assertThat(candidates.getFirst().progress().kind()).isEqualTo(ProgressKind.MOVING_REFERENCE);
        assertThat(candidates.getLast().progress().kind()).isEqualTo(ProgressKind.ROUTE_PROGRESS);
    }

    @Test
    @DisplayName("상충한 서울 detail이 다른 정상 roster 차량의 번호를 가리켜도 관련 후보를 모두 보류한다")
    void detail_conflict_holds_other_implicated_candidate() {
        useRoute(TransitProvider.SEOUL_BUS, 12L);
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(
                roster("id1", "A", DATA_TIME), roster("id2", "B", DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id1")).thenReturn(detailResponse(detail("id1", "B", "s2", 20, DATA_TIME)));
        when(detailClient.fetchVehicleDetail("id2")).thenReturn(detailResponse(detail("id2", "B", "s2", 20, DATA_TIME)));
        var candidates = find(12L).candidates();
        assertHeld(candidates.getFirst(), ReasonCode.DETAIL_NUMBER_MISMATCH);
        assertHeld(candidates.getLast(), ReasonCode.IDENTITY_CONFLICT);
    }

    @Test
    @DisplayName("같은 간격의 후보는 Provider 응답 순서가 바뀌어도 동일한 번호 순서로 표시한다")
    void ties_remain_stable_across_provider_order() {
        when(tago.fetchVehicleLocations(any())).thenReturn(
                tagoResponse(tago("B", "s3", 30), tago("A", "s1", 10)),
                tagoResponse(tago("A", "s1", 10), tago("B", "s3", 30)));
        assertThat(find(12L).candidates()).extracting(Candidate::vehicleNumber).containsExactly("A", "B");
        assertThat(find(12L).candidates()).extracting(Candidate::vehicleNumber).containsExactly("A", "B");
    }

    private void useRoute(TransitProvider provider, Long boardingId) {
        when(metadata.read(1L, 14L, boardingId)).thenReturn(route(provider, boardingId));
    }

    private BoardingCandidatesResponse find(Long boardingId) {
        return service.find(7L, 1L, 14L, boardingId);
    }

    private Context context(Long boardingId) {
        return new Context(7L, 1L, 14L, boardingId);
    }

    private void assertHeld(Candidate candidate, ReasonCode reason) {
        assertThat(candidate.confirmationStatus()).isEqualTo(ConfirmationStatus.HELD);
        assertThat(candidate.candidateRef()).isNull();
        assertThat(candidate.reasonCode()).isEqualTo(reason);
        assertThat(candidate.progress()).isNull();
        assertThat(candidate.boardingOccurrenceGap()).isNull();
        assertThat(candidate.trackingSupport()).isEqualTo(TrackingSupport.HELD);
    }
}
