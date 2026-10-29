package com.stopbell.transit.service;

import static com.stopbell.transit.service.BoardingCandidateTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.stopbell.transit.client.SeoulBusVehicleDetailClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.TagoVehicleLocationClient;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.BoardingCandidatesResponse;
import com.stopbell.transit.dto.BoardingCandidatesResponse.Candidate;
import com.stopbell.transit.dto.BoardingCandidatesResponse.CandidateSetStatus;
import com.stopbell.transit.dto.BoardingCandidatesResponse.ConfirmationStatus;
import com.stopbell.transit.dto.BoardingCandidatesResponse.ReasonCode;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AUD-07 characterization: virtual Provider time, not a live latency benchmark or HTTP timer test. */
class BoardingCandidateLatencyTest {
    private static final DateTimeFormatter DATA_TIME_FORMAT = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withZone(ZoneId.of("Asia/Seoul"));
    private static final Duration FLUTTER_TIMEOUT = Duration.ofSeconds(15);
    private final BoardingCandidateMetadataService metadata = mock(BoardingCandidateMetadataService.class);
    private final TagoVehicleLocationClient tago = mock(TagoVehicleLocationClient.class);
    private final SeoulBusVehicleLocationClient seoul = mock(SeoulBusVehicleLocationClient.class);
    private final SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
    private final MutableClock clock = new MutableClock();
    private final BoardingCandidateReferenceStore references = new BoardingCandidateReferenceStore(clock);
    private final BoardingCandidateService service = new BoardingCandidateService(
            metadata, tago, seoul, detailClient, references, clock);
    private final List<Call> calls = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 20, 40})
    @DisplayName("정상 차량별 1초 지연이 순차 누적되며 15초 뒤에도 후속 detail을 호출한다")
    void normal_details_accumulate(int count) {
        prepare(count, 12L, 1_000, 1_000, Map.of());

        var response = find(12L);

        assertThat(elapsed()).isEqualTo(Duration.ofSeconds(1L + count));
        assertCalls(count);
        assertThat(calls).extracting(Call::vehicleId).containsExactlyElementsOf(ids(count));
        assertThat(calls).extracting(Call::startedAt).containsExactlyElementsOf(
                IntStream.rangeClosed(1, count).mapToObj(NOW::plusSeconds).toList());
        assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.AVAILABLE);
        assertReferences(response, count);
        if (count >= 20) {
            assertThat(elapsed()).isGreaterThan(FLUTTER_TIMEOUT);
            assertThat(calls.stream().filter(call -> !call.startedAt().isBefore(NOW.plus(FLUTTER_TIMEOUT))))
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("중간 차량 하나의 느린 응답이 뒤 차량의 평가와 전체 후보 반환을 지연시킨다")
    void slow_middle_vehicle_blocks_later_details() {
        prepare(5, 12L, 1_000, 100, Map.of(id(3), new Outcome(4_900, null)));

        var response = find(12L);

        assertThat(elapsed()).isEqualTo(Duration.ofMillis(6_300));
        assertThat(calls.get(3).startedAt()).isEqualTo(NOW.plusMillis(6_100));
        assertCalls(5);
        assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.AVAILABLE);
        assertReferences(response, 5);
    }

    @Test
    @DisplayName("단일 detail의 5초 timeout은 후속 차량을 중단하지 않고 실패 차량만 보류한다")
    void one_timeout_preserves_other_candidates_without_retry() {
        prepare(5, 12L, 1_000, 100, Map.of(id(3), timeout()));

        var response = find(12L);

        assertThat(elapsed()).isEqualTo(Duration.ofMillis(6_400));
        assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.PARTIAL);
        assertHeld(response, 3, ReasonCode.DETAIL_TRANSPORT_FAILURE);
        assertReferences(response, 4);
        assertCalls(5);
    }

    @Test
    @DisplayName("정상과 지연과 transport 및 protocol 실패가 섞여도 유효 후보와 원래 시각을 보존한다")
    void mixed_results_keep_identity_and_partial_status() {
        prepare(5, 12L, 1_000, 100, Map.of(
                id(2), new Outcome(4_900, null), id(3), timeout(),
                id(4), new Outcome(100, ReasonCode.DETAIL_PROTOCOL_FAILURE)));

        var response = find(12L);

        assertThat(elapsed()).isEqualTo(Duration.ofMillis(11_200));
        assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.PARTIAL);
        assertHeld(response, 3, ReasonCode.DETAIL_TRANSPORT_FAILURE);
        assertHeld(response, 4, ReasonCode.DETAIL_PROTOCOL_FAILURE);
        assertThat(response.observedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(response.candidates()).allMatch(candidate -> candidate.observedAt().equals(response.observedAt()));
        assertReferences(response, 3);
        assertCalls(5);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 20, 40})
    @DisplayName("연속 5초 timeout은 15초를 초과하며 60초 freshness 경계 뒤 남은 detail만 생략한다")
    void timeouts_have_freshness_cutoff_but_no_screen_deadline(int count) {
        Map<String, Outcome> outcomes = ids(count).stream().collect(
                java.util.stream.Collectors.toMap(vehicleId -> vehicleId, vehicleId -> timeout()));
        prepare(count, 12L, 5_000, 100, outcomes);

        var response = find(12L);

        // Roster observed/source time = NOW+5s. Starts at +5, +10, ... +65 are still fresh.
        int requested = Math.min(count, 13);
        assertCalls(requested);
        assertThat(elapsed()).isEqualTo(Duration.ofSeconds(5L + requested * 5L));
        assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.PARTIAL);
        assertThat(response.candidates()).hasSize(count).allSatisfy(candidate -> {
            assertThat(candidate.confirmationStatus()).isEqualTo(ConfirmationStatus.HELD);
            assertThat(candidate.candidateRef()).isNull();
            assertThat(candidate.selectionKey()).isNull();
            assertThat(candidate.reasonCode()).isEqualTo(count >= 13
                    ? ReasonCode.STALE_OBSERVATION : ReasonCode.DETAIL_TRANSPORT_FAILURE);
        });
    }

    @Test
    @DisplayName("마지막 느린 실패까지 기다리는 동안 이미 성공한 detail도 roster 만료로 선택할 수 없게 된다")
    void late_failure_expires_earlier_successes() {
        prepare(20, 12L, 1_000, 4_900, Map.of(id(13), timeout()));

        var response = find(12L);

        assertThat(elapsed()).isEqualTo(Duration.ofMillis(64_800));
        assertCalls(13);
        assertThat(response.candidates()).hasSize(20).allSatisfy(candidate -> {
            assertThat(candidate.reasonCode()).isEqualTo(ReasonCode.STALE_OBSERVATION);
            assertThat(candidate.candidateRef()).isNull();
        });
        assertThat(response.observedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("응답이 빠르면 차량 80대 모두 detail을 요청하므로 고정 호출 수 상한은 없다")
    void fast_large_roster_has_no_fixed_call_cap() {
        prepare(80, 12L, 1_000, 10, Map.of());

        var response = find(12L);

        assertThat(elapsed()).isEqualTo(Duration.ofMillis(1_800));
        assertCalls(80);
        assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.AVAILABLE);
        assertReferences(response, 80);
    }

    @Test
    @DisplayName("승차 occurrence가 없으면 다수 차량도 roster 한 번만 조회하고 detail을 요청하지 않는다")
    void number_only_lookup_needs_one_roster() {
        prepare(80, null, 1_000, 10, Map.of());

        var response = find(null);

        assertThat(elapsed()).isEqualTo(Duration.ofSeconds(1));
        assertCalls(0);
        assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.AVAILABLE);
        assertThat(response.candidates()).hasSize(80).allSatisfy(candidate -> {
            assertThat(candidate.reasonCode()).isEqualTo(ReasonCode.DETAIL_NOT_REQUESTED);
            assertThat(candidate.progress()).isNull();
        });
    }

    private void prepare(int count, Long boardingId, long rosterMillis, long detailMillis,
            Map<String, Outcome> outcomes) {
        when(metadata.read(1L, 14L, boardingId)).thenReturn(route(TransitProvider.SEOUL_BUS, boardingId));
        when(seoul.fetchVehicleLocations(any())).thenAnswer(invocation -> {
            advance(rosterMillis);
            var items = IntStream.rangeClosed(1, count)
                    .mapToObj(index -> roster(id(index), number(index), DATA_TIME_FORMAT.format(clock.instant())))
                    .toArray(SeoulBusVehicleLocationItem[]::new);
            return rosterResponse(items);
        });
        when(detailClient.fetchVehicleDetail(any())).thenAnswer(invocation -> {
            String vehicleId = invocation.getArgument(0);
            calls.add(new Call(vehicleId, clock.instant()));
            Outcome outcome = outcomes.getOrDefault(vehicleId, new Outcome(detailMillis, null));
            advance(outcome.millis());
            if (outcome.failure() == ReasonCode.DETAIL_TRANSPORT_FAILURE) {
                throw TransitProviderClientException.transport(TransitProvider.SEOUL_BUS,
                        "getBusPosByVehIdItem", new HttpTimeoutException("Fake detail timeout"));
            }
            if (outcome.failure() == ReasonCode.DETAIL_PROTOCOL_FAILURE) {
                throw TransitProviderClientException.protocol(TransitProvider.SEOUL_BUS, "getBusPosByVehIdItem", null);
            }
            int index = Integer.parseInt(vehicleId.substring(1));
            return detailResponse(detail(vehicleId, number(index), "s2", 20,
                    DATA_TIME_FORMAT.format(clock.instant())));
        });
    }

    private void assertReferences(BoardingCandidatesResponse response, int available) {
        var candidates = response.candidates().stream()
                .filter(candidate -> candidate.confirmationStatus() == ConfirmationStatus.AVAILABLE).toList();
        assertThat(candidates).hasSize(available);
        assertThat(candidates).extracting(Candidate::selectionKey).doesNotHaveDuplicates();
        assertThat(candidates).allSatisfy(candidate -> {
            var snapshot = references.resolve(candidate.candidateRef(), 1L).orElseThrow();
            assertThat(snapshot.context()).isEqualTo(new BoardingCandidateReferenceStore.Context(1L, 1L, 14L, 12L));
            assertThat(snapshot.route().externalRouteId()).isEqualTo("external-route");
            assertThat(snapshot.rosterVehicle().id()).isEqualTo(snapshot.detailVehicle().id());
            assertThat(snapshot.rosterVehicle().number()).isEqualTo(candidate.vehicleNumber());
            assertThat(snapshot.detailVehicle().number()).isEqualTo(candidate.vehicleNumber());
            assertThat(candidate.selectionKey()).isEqualTo(
                    snapshot.route().selectionKey(snapshot.rosterVehicle().id(), candidate.vehicleNumber()));
            assertThat(candidate.progress().occurrenceId()).isEqualTo(12L);
            assertThat(candidate.boardingOccurrenceGap()).isZero();
            assertThat(candidate.providerDataTime()).isEqualTo(snapshot.detailVehicle().providerDataTime());
        });
    }

    private void assertHeld(BoardingCandidatesResponse response, int index, ReasonCode reason) {
        var candidate = response.candidates().stream().filter(item -> number(index).equals(item.vehicleNumber()))
                .findFirst().orElseThrow();
        assertThat(candidate.confirmationStatus()).isEqualTo(ConfirmationStatus.HELD);
        assertThat(candidate.reasonCode()).isEqualTo(reason);
        assertThat(candidate.candidateRef()).isNull();
        assertThat(candidate.selectionKey()).isNull();
    }

    private void assertCalls(int count) {
        verify(seoul).fetchVehicleLocations(any());
        verify(detailClient, times(count)).fetchVehicleDetail(any());
        assertThat(calls).hasSize(count);
        assertThat(calls).extracting(Call::vehicleId).doesNotHaveDuplicates();
        verifyNoMoreInteractions(seoul, detailClient);
        verifyNoInteractions(tago);
    }

    private BoardingCandidatesResponse find(Long boardingId) {
        return service.find(1L, 1L, 14L, boardingId);
    }

    private Duration elapsed() {
        return Duration.between(NOW, clock.instant());
    }

    private void advance(long millis) {
        clock.now = clock.now.plusMillis(millis);
    }

    private static String id(int index) {
        return "v%03d".formatted(index);
    }

    private static String number(int index) {
        return "서울74사%04d".formatted(index);
    }

    private static List<String> ids(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(BoardingCandidateLatencyTest::id).toList();
    }

    private static Outcome timeout() {
        return new Outcome(5_000, ReasonCode.DETAIL_TRANSPORT_FAILURE);
    }

    private record Outcome(long millis, ReasonCode failure) {
    }

    private record Call(String vehicleId, Instant startedAt) {
    }
}
