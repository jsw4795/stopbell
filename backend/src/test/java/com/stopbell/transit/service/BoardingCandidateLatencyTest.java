package com.stopbell.transit.service;

import static com.stopbell.transit.service.BoardingCandidateTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import com.stopbell.transit.client.*;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.BoardingCandidatesResponse;
import com.stopbell.transit.dto.BoardingCandidatesResponse.*;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AUD-07: controlled monotonic Provider waves, not live HTTP/operational measurements.
 * Pre-change sequential evidence (2/6/11/21/41s; timeout 10/30/55/70/70s) remains in task-list.md.
 */
class BoardingCandidateLatencyTest {
    private final BoardingCandidateMetadataService metadata = mock(BoardingCandidateMetadataService.class);
    private final TagoVehicleLocationClient tago = mock(TagoVehicleLocationClient.class);
    private final SeoulBusVehicleLocationClient seoul = mock(SeoulBusVehicleLocationClient.class);
    private final SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final BoardingCandidateReferenceStore references = new BoardingCandidateReferenceStore(clock);

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 20, 40})
    @DisplayName("정상 detail 1초 모델은 두 개씩 처리하고 12초에서 정상 후보를 보존하며 마감한다")
    void normal_details_obey_budget(int count) throws Exception {
        runWaves(count, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 20, 40})
    @DisplayName("연속 timeout 모델은 확인된 통신 실패와 deadline 미시작 및 미완료를 구분한다")
    void timeout_details_obey_budget(int count) throws Exception {
        runWaves(count, true);
    }

    private void runWaves(int count, boolean timeout) throws Exception {
        var nanos = new AtomicLong();
        var started = new LinkedBlockingQueue<Call>();
        var allCalls = new ArrayList<Call>();
        prepare(count);
        when(seoul.fetchVehicleLocations(any())).thenAnswer(invocation -> {
            nanos.set(Duration.ofSeconds(timeout ? 5 : 1).toNanos());
            return rosterResponse(items(count));
        });
        when(detailClient.fetchVehicleDetail(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            var call = new Call(id, new CountDownLatch(1));
            started.add(call);
            BoardingCandidateDetailExecutorTest.await(call.release());
            if (timeout) throw TransitProviderClientException.transport(TransitProvider.SEOUL_BUS, "detail", null);
            return detailResponse(detail(id, number(Integer.parseInt(id.substring(1))), "s2", 20, DATA_TIME));
        });
        try (var executor = new BoardingCandidateDetailExecutor(Duration.ofSeconds(12), nanos::get);
                var caller = Executors.newSingleThreadExecutor()) {
            var service = service(executor);
            var future = caller.submit(() -> service.find(1L, 1L, 14L, 12L));
            int calls = 0;
            while (calls < count && nanos.get() < Duration.ofSeconds(12).toNanos()) {
                int waveSize = Math.min(2, count - calls);
                List<Call> wave = new ArrayList<>();
                for (int i = 0; i < waveSize; i++) {
                    Call call = started.poll(2, TimeUnit.SECONDS);
                    assertThat(call).isNotNull();
                    wave.add(call);
                    allCalls.add(call);
                }
                calls += waveSize;
                nanos.set(Math.min(Duration.ofSeconds(12).toNanos(),
                        nanos.get() + Duration.ofSeconds(timeout ? 5 : 1).toNanos()));
                wave.forEach(call -> call.release().countDown());
            }
            var response = future.get(2, TimeUnit.SECONDS);
            long expectedSeconds = timeout ? count == 1 ? 10 : 12 : Math.min(12, 1 + (count + 1) / 2);
            assertThat(Duration.ofNanos(nanos.get())).isEqualTo(Duration.ofSeconds(expectedSeconds));
            assertThat(Duration.ofNanos(nanos.get())).isLessThan(Duration.ofSeconds(15));
            assertThat(response.candidates()).hasSize(count);
            assertThat(response.candidates()).extracting(Candidate::vehicleNumber)
                    .containsExactlyElementsOf(IntStream.rangeClosed(1, count).mapToObj(BoardingCandidateLatencyTest::number).toList());
            int available = timeout ? 0 : Math.min(count, 20);
            int requested = timeout ? Math.min(count, 4) : Math.min(count, 22);
            assertThat(allCalls).hasSize(requested);
            verify(detailClient, times(requested)).fetchVehicleDetail(any());
            assertThat(response.candidates().stream().filter(c -> c.confirmationStatus() == ConfirmationStatus.AVAILABLE))
                    .hasSize(available).allSatisfy(candidate -> assertReference(candidate, 1L));
            assertThat(response.candidateSetStatus()).isEqualTo(available == count
                    ? CandidateSetStatus.AVAILABLE : CandidateSetStatus.PARTIAL);
            assertThat(response.candidates().stream().filter(c -> c.confirmationStatus() == ConfirmationStatus.HELD))
                    .allSatisfy(this::assertHeld);
            if (timeout) {
                assertThat(response.candidates().stream().filter(c -> c.reasonCode() == ReasonCode.DETAIL_TRANSPORT_FAILURE))
                        .hasSize(Math.min(count, 2));
            }
            assertThat(response.candidates().stream().filter(c -> c.reasonCode() == ReasonCode.DETAIL_DEADLINE_NOT_STARTED))
                    .hasSize(count - requested);
            assertThat(response.candidates().stream().filter(c -> c.reasonCode() == ReasonCode.DETAIL_DEADLINE_INCOMPLETE))
                    .hasSize(requested - available - (timeout ? Math.min(count, 2) : 0));
            assertThat(response.observedAt()).isEqualTo(NOW);
            assertThat(response.candidates()).allMatch(c -> c.observedAt().equals(NOW) && c.providerDataTime().equals(NOW));
        } finally {
            allCalls.forEach(call -> call.release().countDown());
        }
    }

    @Test
    @DisplayName("응답 없는 차량에도 빠른 후속 차량은 완료되며 늦은 detail은 재조회 reference를 교체하지 못한다")
    void slow_vehicle_preserves_success_and_late_results_cannot_issue_references() throws Exception {
        prepare(5);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        when(detailClient.fetchVehicleDetail(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            if (id.equals("v1")) {
                try {
                    BoardingCandidateDetailExecutorTest.await(release);
                } finally {
                    finished.countDown();
                }
            }
            return detailResponse(detail(id, number(Integer.parseInt(id.substring(1))), "s2", 20, DATA_TIME));
        });
        try (var executor = new BoardingCandidateDetailExecutor(Duration.ofMillis(200), System::nanoTime)) {
            var response = service(executor).find(1L, 1L, 14L, 12L);
            assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.PARTIAL);
            assertThat(response.candidates().getLast().reasonCode()).isEqualTo(ReasonCode.DETAIL_DEADLINE_INCOMPLETE);
            assertHeld(response.candidates().getLast());
            assertThat(response.candidates().subList(0, 4)).allSatisfy(c -> assertReference(c, 1L));
            when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(items(1)));
            doReturn(detailResponse(detail("v1", number(1), "s2", 20, DATA_TIME)))
                    .when(detailClient).fetchVehicleDetail("v1");
            var fresh = service(executor).find(1L, 1L, 14L, 12L).candidates().getFirst();
            release.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            executor.close();
            var pool = (java.util.concurrent.ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils
                    .getField(executor, "executor");
            assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertReference(fresh, 1L);
            assertThat(response.candidates().subList(0, 4)).allSatisfy(c ->
                    assertThat(references.resolve(c.candidateRef(), 1L)).isEmpty());
            verify(detailClient, times(6)).fetchVehicleDetail(any());
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("Metadata와 roster가 소비한 단일 예산은 초기화하지 않으며 비어 있지 않은 roster는 전체 보류로 반환한다")
    void exhausted_earlier_stages_do_not_start_details() {
        var nanos = new AtomicLong();
        prepare(5);
        when(metadata.read(1L, 14L, 12L)).thenAnswer(invocation -> {
            nanos.set(Duration.ofSeconds(8).toNanos());
            return route(TransitProvider.SEOUL_BUS, 12L);
        });
        when(seoul.fetchVehicleLocations(any())).thenAnswer(invocation -> {
            nanos.addAndGet(Duration.ofSeconds(4).toNanos());
            return rosterResponse(items(5));
        });
        try (var executor = new BoardingCandidateDetailExecutor(Duration.ofSeconds(12), nanos::get)) {
            var response = service(executor).find(1L, 1L, 14L, 12L);
            assertThat(response.candidateSetStatus()).isEqualTo(CandidateSetStatus.PARTIAL);
            assertThat(response.candidates()).hasSize(5).allSatisfy(candidate -> {
                assertHeld(candidate);
                assertThat(candidate.reasonCode()).isEqualTo(ReasonCode.DETAIL_DEADLINE_NOT_STARTED);
            });
            verifyNoInteractions(detailClient);
            var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            var json = mapper.readTree(mapper.writeValueAsString(response));
            assertThat(json.get("candidateSetStatus").asText()).isEqualTo("PARTIAL");
            var item = json.get("candidates").get(0);
            assertThat(item.get("reasonCode").asText()).isEqualTo("DETAIL_DEADLINE_NOT_STARTED");
            for (String field : List.of("candidateRef", "selectionKey", "progress", "boardingOccurrenceGap")) {
                assertThat(item.get(field).isNull()).isTrue();
            }
            assertThat(item.has("vehicleId")).isFalse();
            assertThat(json.has("externalRouteId")).isFalse();
        }
    }

    @Test
    @DisplayName("동시 사용자와 Route의 차량 결과 및 reference 문맥은 서로 섞이지 않는다")
    void concurrent_contexts_keep_vehicle_identity() throws Exception {
        var original = route(TransitProvider.SEOUL_BUS, 12L);
        var other = new BoardingRouteSnapshot(2L, TransitProvider.SEOUL_BUS, "other-route", null,
                14L, 12L, original.traversal(), "other-number");
        when(metadata.read(1L, 14L, 12L)).thenReturn(original);
        when(metadata.read(2L, 14L, 12L)).thenReturn(other);
        when(seoul.fetchVehicleLocations(any())).thenAnswer(invocation -> {
            VehicleLocationRequest<?> request = invocation.getArgument(0);
            String prefix = request.externalRouteId().equals("other-route") ? "b" : "a";
            return rosterResponse(roster(prefix + "1", prefix + "-1", DATA_TIME),
                    roster(prefix + "2", prefix + "-2", DATA_TIME));
        });
        var allStarted = new CountDownLatch(4);
        when(detailClient.fetchVehicleDetail(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            allStarted.countDown();
            BoardingCandidateDetailExecutorTest.await(allStarted);
            return detailResponse(detail(id, id.substring(0, 1) + "-" + id.substring(1), "s2", 20, DATA_TIME));
        });
        try (var executor = new BoardingCandidateDetailExecutor(); var callers = Executors.newFixedThreadPool(2)) {
            var service = service(executor);
            var first = callers.submit(() -> service.find(1L, 1L, 14L, 12L));
            var second = callers.submit(() -> service.find(2L, 2L, 14L, 12L));
            for (var entry : List.of(new ContextResult(first.get(2, TimeUnit.SECONDS), 1L, "a"),
                    new ContextResult(second.get(2, TimeUnit.SECONDS), 2L, "b"))) {
                assertThat(entry.response().candidateSetStatus()).isEqualTo(CandidateSetStatus.AVAILABLE);
                assertThat(entry.response().candidates()).extracting(Candidate::vehicleNumber)
                        .containsExactly(entry.prefix() + "-1", entry.prefix() + "-2");
                for (var candidate : entry.response().candidates()) {
                    var snapshot = references.resolve(candidate.candidateRef(), entry.id()).orElseThrow();
                    assertThat(snapshot.context()).isEqualTo(new BoardingCandidateReferenceStore.Context(
                            entry.id(), entry.id(), 14L, 12L));
                    assertThat(snapshot.route().routeId()).isEqualTo(entry.id());
                    assertThat(snapshot.rosterVehicle().id()).isEqualTo(snapshot.detailVehicle().id());
                    assertThat(snapshot.detailVehicle().number()).isEqualTo(candidate.vehicleNumber());
                    assertThat(candidate.selectionKey()).isEqualTo(snapshot.route().selectionKey(
                            snapshot.rosterVehicle().id(), candidate.vehicleNumber()));
                }
            }
        }
    }

    @Test
    @DisplayName("Roster 자체의 실패는 Partial 목록으로 바꾸지 않고 기존 일시 불가 오류를 유지한다")
    void roster_failure_keeps_existing_error_policy() {
        prepare(1);
        when(seoul.fetchVehicleLocations(any())).thenThrow(
                TransitProviderClientException.transport(TransitProvider.SEOUL_BUS, "roster", null));
        try (var executor = new BoardingCandidateDetailExecutor()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(executor).find(1L, 1L, 14L, 12L))
                    .isInstanceOfSatisfying(com.stopbell.common.error.ApiException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(
                                    com.stopbell.common.error.ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE));
            verifyNoInteractions(detailClient);
        }
    }

    private record ContextResult(BoardingCandidatesResponse response, Long id, String prefix) {
    }

    private void prepare(int count) {
        when(metadata.read(1L, 14L, 12L)).thenReturn(route(TransitProvider.SEOUL_BUS, 12L));
        when(seoul.fetchVehicleLocations(any())).thenReturn(rosterResponse(items(count)));
    }

    private BoardingCandidateService service(BoardingCandidateDetailExecutor executor) {
        return new BoardingCandidateService(metadata, tago, seoul, detailClient, references, clock, executor);
    }

    private static SeoulBusVehicleLocationItem[] items(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> roster("v" + i, number(i), DATA_TIME))
                .toArray(SeoulBusVehicleLocationItem[]::new);
    }

    private static String number(int index) {
        return "서울74사%04d".formatted(index);
    }

    private void assertReference(Candidate candidate, Long userId) {
        var snapshot = references.resolve(candidate.candidateRef(), userId).orElseThrow();
        assertThat(snapshot.context()).isEqualTo(new BoardingCandidateReferenceStore.Context(userId, 1L, 14L, 12L));
        assertThat(snapshot.rosterVehicle().id()).isEqualTo(snapshot.detailVehicle().id());
        assertThat(snapshot.detailVehicle().number()).isEqualTo(candidate.vehicleNumber());
        assertThat(candidate.selectionKey()).isEqualTo(snapshot.route().selectionKey(snapshot.rosterVehicle().id(), candidate.vehicleNumber()));
        assertThat(candidate.progress().occurrenceId()).isEqualTo(12L);
        assertThat(candidate.boardingOccurrenceGap()).isZero();
        assertThat(references.resolve(candidate.candidateRef(), userId + 1)).isEmpty();
    }

    private void assertHeld(Candidate candidate) {
        assertThat(candidate.confirmationStatus()).isEqualTo(ConfirmationStatus.HELD);
        assertThat(candidate.trackingSupport()).isEqualTo(TrackingSupport.HELD);
        assertThat(candidate.candidateRef()).isNull();
        assertThat(candidate.selectionKey()).isNull();
        assertThat(candidate.progress()).isNull();
        assertThat(candidate.boardingOccurrenceGap()).isNull();
    }

    private record Call(String id, CountDownLatch release) {
    }
}
