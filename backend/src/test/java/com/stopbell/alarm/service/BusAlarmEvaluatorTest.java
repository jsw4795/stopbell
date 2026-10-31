package com.stopbell.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import com.stopbell.alarm.entity.AdjacentStopSnapshot;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.transit.domain.ArrivalEvidence;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitObservation;
import com.stopbell.transit.domain.TransitProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BusAlarmEvaluatorTest {

    private static final UUID TRACKING_CYCLE_ID = UUID.fromString("a1234567-1234-4123-8123-123456789abc");

    private static final Instant NOW = Instant.parse("2026-09-23T03:00:00Z");

    private final BusRouteTraversalService routeTraversalService = mock(BusRouteTraversalService.class);
    private final BusAlarmEvaluator evaluator = new BusAlarmEvaluator(routeTraversalService);

    @Test
    @DisplayName("detail 재시도 부분 평가는 다른 차량을 소실로 처리하거나 오래된 roster를 다시 touch하지 않는다")
    void partial_detail_retry_preserves_unrelated_vehicle_presence() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        TransitObservation older = observation(TransitProvider.SEOUL_BUS, "other", "predecessor", 10,
                ArrivalEvidence.MOVING, NOW.minusSeconds(55));
        BusAlarmEvaluationState state = new BusAlarmEvaluationState(true,
                java.util.Map.of("other", VehicleTrackingState.beginBeforeTarget(older, NOW.minusSeconds(55))),
                java.util.Map.of(), java.util.Map.of());

        BusAlarmEvaluationResult result = evaluator.evaluatePartial(alarm, state, Set.of("retried"),
                List.of(observation(TransitProvider.SEOUL_BUS, "retried", "predecessor", 10,
                        ArrivalEvidence.MOVING, NOW)), NOW.plusSeconds(6));

        assertThat(result.nextState().trackedVehicles()).containsKeys("other", "retried");
        assertThat(result.nextState().trackedVehicles().get("other").lastSeenAt()).isEqualTo(NOW.minusSeconds(55));
    }

    @Test
    @DisplayName("baseline predecessor는 ONE_STOP_BEFORE 후보를 만들고 같은 차량을 계속 추적한다")
    void emits_before_at_baseline_and_keeps_tracking() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);

        BusAlarmEvaluationResult result = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW));

        assertThat(result.eventCandidates()).extracting(event -> event.type())
                .containsExactly(TransitEventType.ONE_STOP_BEFORE);
        assertThat(result.nextState().trackedVehicles()).containsKey("vehicle");
    }

    @Test
    @DisplayName("ONE_STOP_BEFORE는 predecessor snapshot과 정확히 일치할 때만 한 cycle에 한 번 만든다")
    void uses_predecessor_snapshot_and_deduplicates_before() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        BusAlarmEvaluationResult baseline = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW));

        BusAlarmEvaluationResult repeated = evaluate(alarm, baseline.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW.plusSeconds(1)));

        assertThat(repeated.eventCandidates()).isEmpty();
        assertThat(repeated.nextState().trackedVehicles()).containsKey("vehicle");
    }

    @Test
    @DisplayName("서울 direct ARRIVED는 baseline target에서 즉시 ARRIVED 후보를 만든다")
    void emits_seoul_direct_arrived_at_baseline() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);

        BusAlarmEvaluationResult result = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20, ArrivalEvidence.ARRIVED, NOW));

        assertThat(result.eventCandidates()).extracting(event -> event.type()).containsExactly(TransitEventType.ARRIVED);
    }

    @Test
    @DisplayName("서울 MOVING target observation은 ARRIVED로 승격하지 않는다")
    void does_not_promote_seoul_moving_to_arrived() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);

        BusAlarmEvaluationResult result = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20, ArrivalEvidence.MOVING, NOW));

        assertThat(result.eventCandidates()).isEmpty();
    }

    @Test
    @DisplayName("TAGO baseline target은 100m 이내 GPS corroboration이 있을 때만 ARRIVED 후보를 만든다")
    void emits_tago_arrived_only_with_nearby_gps_at_baseline() {
        Alarm alarm = activeAlarm(TransitProvider.TAGO);

        BusAlarmEvaluationResult nearby = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.TAGO, "vehicle", "target", 20, ArrivalEvidence.UNAVAILABLE, NOW));
        BusAlarmEvaluationResult distant = evaluate(alarm, BusAlarmEvaluationState.initial(), tagoObservation(
                "vehicle", "target", 20, new BigDecimal("37.0100000"), new BigDecimal("127.0000000"), NOW));

        assertThat(nearby.eventCandidates()).extracting(event -> event.type()).containsExactly(TransitEventType.ARRIVED);
        assertThat(distant.eventCandidates()).isEmpty();
    }

    @Test
    @DisplayName("TAGO baseline target에 GPS가 없으면 false ARRIVED를 만들지 않는다")
    void does_not_emit_tago_arrived_without_baseline_gps() {
        Alarm alarm = activeAlarm(TransitProvider.TAGO);

        BusAlarmEvaluationResult result = evaluate(alarm, BusAlarmEvaluationState.initial(), new TransitObservation(
                TransitProvider.TAGO, "route", "vehicle", "target", 20, null, null, null,
                null, null, NOW, null, ArrivalEvidence.UNAVAILABLE
        ));

        assertThat(result.eventCandidates()).isEmpty();
    }

    @Test
    @DisplayName("같은 차량이 target 이전에서 이후로 진행하면 PASSED 후보와 metadata edge count를 만든다")
    void emits_passed_for_tracked_vehicle_and_uses_metadata_edge_count() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        when(routeTraversalService.stopsPastTarget(any(), any())).thenReturn(OptionalInt.of(2));
        BusAlarmEvaluationResult baseline = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW));

        BusAlarmEvaluationResult result = evaluate(alarm, baseline.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "later-stop", 90, ArrivalEvidence.MOVING, NOW.plusSeconds(1)));

        assertThat(result.eventCandidates()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(TransitEventType.PASSED);
            assertThat(event.stopsPastTarget()).isEqualTo(2);
        });
        assertThat(result.nextState().trackedVehicles()).doesNotContainKey("vehicle");
    }

    @Test
    @DisplayName("target과 같은 order의 다른 Stop ID는 UNKNOWN으로 보존하고 PASSED를 만들지 않는다")
    void preserves_unknown_for_target_order_conflict() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        BusAlarmEvaluationResult baseline = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW));

        BusAlarmEvaluationResult conflict = evaluate(alarm, baseline.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "different-stop", 20, ArrivalEvidence.MOVING,
                        NOW.plusSeconds(1)));

        assertThat(conflict.eventCandidates()).isEmpty();
        assertThat(conflict.nextState().trackedVehicles().get("vehicle").hasObservedBeforeTarget()).isTrue();
        assertThat(conflict.nextState().trackedVehicles().get("vehicle").lastObservation().currentStopExternalId())
                .isEqualTo("predecessor");
    }

    @Test
    @DisplayName("target과 같은 Stop ID의 다른 order는 계속 UNKNOWN으로 보존한다")
    void preserves_unknown_for_target_stop_id_conflict() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);

        BusAlarmEvaluationResult result = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "target", 21, ArrivalEvidence.MOVING, NOW));

        assertThat(result.eventCandidates()).isEmpty();
        assertThat(result.nextState().trackedVehicles()).isEmpty();
        assertThat(result.nextState().baselineAfterVehicles()).isEmpty();
    }

    @Test
    @DisplayName("predecessor와 같은 order의 다른 Stop ID는 ONE_STOP_BEFORE 후보를 만들지 않는다")
    void does_not_emit_before_for_predecessor_order_conflict() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);

        BusAlarmEvaluationResult result = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "different-stop", 10, ArrivalEvidence.MOVING, NOW));

        assertThat(result.eventCandidates()).isEmpty();
        assertThat(result.nextState().trackedVehicles()).isEmpty();
    }

    @Test
    @DisplayName("target 이전 history는 non-arrived target observation 뒤에도 유지되어 PASSED로 이어진다")
    void emits_passed_after_non_arrived_target_observation() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        when(routeTraversalService.stopsPastTarget(any(), any())).thenReturn(OptionalInt.of(1));
        BusAlarmEvaluationResult before = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "earlier-stop", 5, ArrivalEvidence.MOVING, NOW));
        BusAlarmEvaluationResult atTarget = evaluate(alarm, before.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20, ArrivalEvidence.MOVING,
                        NOW.plusSeconds(1)));

        BusAlarmEvaluationResult after = evaluate(alarm, atTarget.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "later-stop", 90, ArrivalEvidence.MOVING,
                        NOW.plusSeconds(2)));

        assertThat(atTarget.eventCandidates()).isEmpty();
        assertThat(atTarget.nextState().trackedVehicles().get("vehicle").hasObservedBeforeTarget()).isTrue();
        assertThat(after.eventCandidates()).extracting(event -> event.type()).containsExactly(TransitEventType.PASSED);
    }

    @Test
    @DisplayName("ONE_STOP_BEFORE 뒤에도 target 이전 history를 유지해 PASSED로 이어진다")
    void preserves_before_history_after_emitting_one_stop_before() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        BusAlarmEvaluationResult before = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW));
        BusAlarmEvaluationResult atTarget = evaluate(alarm, before.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20, ArrivalEvidence.MOVING,
                        NOW.plusSeconds(1)));

        BusAlarmEvaluationResult after = evaluate(alarm, atTarget.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "later-stop", 90, ArrivalEvidence.MOVING,
                        NOW.plusSeconds(2)));

        assertThat(before.eventCandidates()).extracting(event -> event.type())
                .containsExactly(TransitEventType.ONE_STOP_BEFORE);
        assertThat(after.eventCandidates()).extracting(event -> event.type()).containsExactly(TransitEventType.PASSED);
    }

    @Test
    @DisplayName("baseline에서 target 이후인 차량은 PASSED를 만들지 않고 missing grace 동안 재추적하지 않는다")
    void suppresses_baseline_after_vehicle_until_missing_grace_expires() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        BusAlarmEvaluationResult baseline = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "later-stop", 90, ArrivalEvidence.MOVING, NOW));

        BusAlarmEvaluationResult returnedBefore = evaluate(alarm, baseline.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW.plusSeconds(1)));

        assertThat(returnedBefore.eventCandidates()).isEmpty();
        assertThat(returnedBefore.nextState().trackedVehicles()).doesNotContainKey("vehicle");
        assertThat(returnedBefore.nextState().baselineAfterVehicles()).containsKey("vehicle");

        BusAlarmEvaluationResult returnedAfter = evaluate(alarm, returnedBefore.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "later-stop", 90, ArrivalEvidence.MOVING,
                        NOW.plusSeconds(2)));

        assertThat(returnedAfter.eventCandidates()).isEmpty();
    }

    @Test
    @DisplayName("roster에만 남은 baseline-after 차량은 missing grace 이후에도 보존한다")
    void preserves_baseline_after_vehicle_when_present_without_detail_observation() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        BusAlarmEvaluationResult baseline = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "later-stop", 90, ArrivalEvidence.MOVING, NOW));

        BusAlarmEvaluationResult presentWithoutDetail = evaluatePresent(alarm, baseline.nextState(), Set.of("vehicle"),
                List.of(), NOW.plusSeconds(61));

        assertThat(presentWithoutDetail.eventCandidates()).isEmpty();
        assertThat(presentWithoutDetail.nextState().baselineAfterVehicles()).containsKey("vehicle");
    }

    @Test
    @DisplayName("상세 조회가 정상 empty여도 roster에 있는 tracked 차량의 위치와 event는 추측하지 않는다")
    void preserves_tracked_vehicle_when_present_without_detail_observation() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        TransitObservation observation = observation(
                TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW
        );
        BusAlarmEvaluationResult baseline = evaluate(alarm, BusAlarmEvaluationState.initial(), observation);

        BusAlarmEvaluationResult presentWithoutDetail = evaluatePresent(alarm, baseline.nextState(), Set.of("vehicle"),
                List.of(), NOW.plusSeconds(61));

        VehicleTrackingState tracking = presentWithoutDetail.nextState().trackedVehicles().get("vehicle");
        assertThat(presentWithoutDetail.eventCandidates()).isEmpty();
        assertThat(tracking.lastObservation()).isEqualTo(observation);
        assertThat(tracking.lastSeenAt()).isEqualTo(NOW.plusSeconds(61));
    }

    @Test
    @DisplayName("roster에서도 사라진 차량은 기존 missing grace 뒤에 제거한다")
    void expires_vehicle_when_not_present_in_roster() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        BusAlarmEvaluationResult baseline = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW));

        BusAlarmEvaluationResult expired = evaluatePresent(alarm, baseline.nextState(), Set.of(), List.of(),
                NOW.plusSeconds(61));

        assertThat(expired.nextState().trackedVehicles()).doesNotContainKey("vehicle");
    }

    @Test
    @DisplayName("직접 ARRIVED evidence는 같은 transition의 PASSED보다 우선한다")
    void gives_arrived_precedence_over_passed() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        BusAlarmEvaluationResult baseline = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW));

        BusAlarmEvaluationResult result = evaluate(alarm, baseline.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20, ArrivalEvidence.ARRIVED, NOW.plusSeconds(1)));
        BusAlarmEvaluationResult after = evaluate(alarm, result.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "later-stop", 90, ArrivalEvidence.MOVING,
                        NOW.plusSeconds(2)));

        assertThat(result.eventCandidates()).extracting(event -> event.type()).containsExactly(TransitEventType.ARRIVED);
        assertThat(after.eventCandidates()).isEmpty();
    }

    @Test
    @DisplayName("order regression과 stale observation은 UNKNOWN으로 보존한다")
    void preserves_unknown_for_order_regression_and_stale_observation() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        BusAlarmEvaluationResult baseline = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW));

        BusAlarmEvaluationResult regression = evaluate(alarm, baseline.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "earlier-stop", 5, ArrivalEvidence.MOVING, NOW.plusSeconds(1)));
        BusAlarmEvaluationResult stale = evaluate(alarm, regression.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20, ArrivalEvidence.ARRIVED,
                        NOW.minusSeconds(61)), NOW);

        assertThat(regression.eventCandidates()).isEmpty();
        assertThat(stale.eventCandidates()).isEmpty();
        assertThat(stale.nextState().trackedVehicles()).containsKey("vehicle");
    }

    @Test
    @DisplayName("서울 providerDataTime이 60초 넘게 stale이면 ARRIVED 후보를 만들지 않는다")
    void rejects_stale_seoul_provider_data_time() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        TransitObservation staleProviderTime = new TransitObservation(
                TransitProvider.SEOUL_BUS, "route", "vehicle", "target", 20, null, null, null,
                null, null, NOW, NOW.minusSeconds(61), ArrivalEvidence.ARRIVED
        );

        BusAlarmEvaluationResult result = evaluate(alarm, BusAlarmEvaluationState.initial(), staleProviderTime);

        assertThat(result.eventCandidates()).isEmpty();
    }

    @Test
    @DisplayName("missing grace가 끝난 뒤 같은 vehicle ID가 다시 target 이전에 나타나면 새 UUID cycle을 시작한다")
    void creates_new_cycle_after_missing_grace_expires() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        BusAlarmEvaluationResult baseline = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW));
        UUID firstCycle = baseline.nextState().trackedVehicles().get("vehicle").trackingCycleId();

        BusAlarmEvaluationResult expired = evaluate(alarm, baseline.nextState(), List.of(),
                NOW.plus(BusAlarmEvaluationPolicy.VEHICLE_MISSING_GRACE).plusSeconds(1));
        BusAlarmEvaluationResult reappeared = evaluate(alarm, expired.nextState(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING,
                        NOW.plusSeconds(62)), NOW.plusSeconds(62));

        assertThat(expired.eventCandidates()).isEmpty();
        assertThat(reappeared.nextState().trackedVehicles().get("vehicle").trackingCycleId()).isNotEqualTo(firstCycle);
    }

    @Test
    @DisplayName("FOLLOW_UP은 persisted vehicle의 successor 도달 또는 jump만 ONE_STOP_AFTER로 평가한다")
    void evaluates_follow_up_successor_and_ignores_other_vehicle() {
        Alarm alarm = followUpAlarm();

        BusAlarmEvaluationResult ignored = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "other", "later-stop", 90, ArrivalEvidence.MOVING, NOW));
        BusAlarmEvaluationResult arrivedAfter = evaluate(alarm, ignored.nextState(),
                observation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "later-stop", 90,
                        ArrivalEvidence.MOVING, NOW.plusSeconds(1)));

        assertThat(ignored.eventCandidates()).isEmpty();
        assertThat(arrivedAfter.eventCandidates().getFirst().trackingCycleId()).isEqualTo(TRACKING_CYCLE_ID);
        assertThat(arrivedAfter.nextState().followUpVehicles().get("follow-up-vehicle").trackingCycleId())
                .isEqualTo(TRACKING_CYCLE_ID);
        assertThat(arrivedAfter.eventCandidates()).extracting(event -> event.type())
                .containsExactly(TransitEventType.ONE_STOP_AFTER);
    }

    @Test
    @DisplayName("FOLLOW_UP successor와 같은 order의 다른 Stop ID는 ONE_STOP_AFTER 후보를 만들지 않는다")
    void does_not_emit_after_for_successor_order_conflict() {
        Alarm alarm = followUpAlarm();

        BusAlarmEvaluationResult result = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "different-stop", 35,
                        ArrivalEvidence.MOVING, NOW));

        assertThat(result.eventCandidates()).isEmpty();
    }

    @Test
    @DisplayName("FOLLOW_UP memory UUID가 persisted cycle과 다르면 새 UUID로 보정하지 않고 거부한다")
    void rejects_follow_up_memory_uuid_mismatch() {
        Alarm alarm = followUpAlarm();
        TransitObservation observation = observation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "successor", 35,
                ArrivalEvidence.MOVING, NOW);
        BusAlarmEvaluationState wrong = new BusAlarmEvaluationState(true, java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of("follow-up-vehicle", VehicleTrackingState.begin(observation, NOW)));
        assertThatThrownBy(() -> evaluate(alarm, wrong, observation)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("FOLLOW_UP timeout은 event 없이 explicit expired result를 만든다")
    void reports_follow_up_timeout_without_event() {
        Alarm alarm = followUpAlarm();

        BusAlarmEvaluationResult result = evaluate(alarm, BusAlarmEvaluationState.initial(), List.of(), NOW.plusSeconds(301));

        assertThat(result.eventCandidates()).isEmpty();
        assertThat(result.followUpExpired()).isTrue();
    }

    @Test
    @DisplayName("다른 Provider 또는 Route observation은 grouping invariant 위반으로 명시적으로 실패한다")
    void rejects_provider_or_route_mismatch() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);

        assertThatThrownBy(() -> evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.TAGO, "vehicle", "predecessor", 10, ArrivalEvidence.UNAVAILABLE, NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider and route");
    }

    @ParameterizedTest
    @CsvSource({
            "SEOUL_BUS, target, 20, ARRIVED, 0", "SEOUL_BUS, target, 20, ARRIVED, -5",
            "TAGO, target, 20, UNAVAILABLE, 0", "TAGO, target, 20, UNAVAILABLE, -5",
            "SEOUL_BUS, later-stop, 90, MOVING, 0", "SEOUL_BUS, later-stop, 90, MOVING, -5",
            "SEOUL_BUS, predecessor, 10, MOVING, 0", "SEOUL_BUS, predecessor, 10, MOVING, -5"
    })
    @DisplayName("동일하거나 역행한 Provider 시각의 정류장 진행은 이벤트 없이 검증된 상태를 보존하고 다음 정상 관측으로 복구한다")
    void audit_rejects_conflicting_progress_and_recovers(TransitProvider provider, String stopId, int order,
            ArrivalEvidence evidence, int providerSeconds) {
        Alarm alarm = activeAlarm(provider);
        TransitObservation before = timedObservation(provider, "vehicle", "earlier-stop", 5,
                ArrivalEvidence.UNAVAILABLE, NOW, NOW);
        BusAlarmEvaluationState state = evaluate(alarm, BusAlarmEvaluationState.initial(), before).nextState();
        VehicleTrackingState original = state.trackedVehicles().get("vehicle");
        TransitObservation conflict = timedObservation(provider, "vehicle", stopId, order, evidence,
                NOW.plusSeconds(10), NOW.plusSeconds(providerSeconds));

        BusAlarmEvaluationResult rejected = evaluate(alarm, state, conflict);

        assertThat(rejected.eventCandidates()).isEmpty();
        assertThat(rejected.nextState().trackedVehicles().get("vehicle"))
                .isEqualTo(original.observe(before, conflict.observedAt()));
        assertThat(rejected.nextState().baselineAfterVehicles()).isEqualTo(state.baselineAfterVehicles());
        assertThat(rejected.nextState().followUpVehicles()).isEmpty();

        if (order > 20) {
            when(routeTraversalService.stopsPastTarget(any(), any())).thenReturn(OptionalInt.of(2));
        }
        TransitObservation valid = timedObservation(provider, "vehicle", stopId, order, evidence,
                NOW.plusSeconds(20), NOW.plusSeconds(15));
        BusAlarmEvaluationResult recovered = evaluate(alarm, rejected.nextState(), valid);
        TransitEventType expected = order == 20 ? TransitEventType.ARRIVED
                : order > 20 ? TransitEventType.PASSED : TransitEventType.ONE_STOP_BEFORE;
        assertThat(recovered.eventCandidates()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(expected);
            assertThat(event.trackingCycleId()).isEqualTo(original.trackingCycleId());
            assertThat(event.providerDataTime()).isEqualTo(valid.providerDataTime());
        });
        assertThat(evaluate(alarm, recovered.nextState(), valid).eventCandidates()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    @DisplayName("FOLLOW_UP의 동일하거나 역행한 시각은 도달 근거와 추적 위치를 바꾸지 않고 정상 후속 관측을 기다린다")
    void audit_rejects_follow_up_time_conflict_and_recovers(int providerSeconds) {
        Alarm alarm = followUpAlarm();
        TransitObservation atTarget = timedObservation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "target", 20,
                ArrivalEvidence.ARRIVED, NOW, NOW);
        BusAlarmEvaluationState state = evaluate(alarm, BusAlarmEvaluationState.initial(), atTarget).nextState();
        VehicleTrackingState original = state.followUpVehicles().get("follow-up-vehicle");
        TransitObservation conflict = timedObservation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "successor", 35,
                ArrivalEvidence.MOVING, NOW.plusSeconds(10), NOW.plusSeconds(providerSeconds));

        BusAlarmEvaluationResult rejected = evaluate(alarm, state, conflict);

        assertThat(rejected.eventCandidates()).isEmpty();
        assertThat(rejected.nextState().followUpVehicles().get("follow-up-vehicle"))
                .isEqualTo(original.observe(atTarget, conflict.observedAt()));
        BusAlarmEvaluationResult recovered = evaluate(alarm, rejected.nextState(),
                timedObservation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "successor", 35,
                        ArrivalEvidence.MOVING, NOW.plusSeconds(20), NOW.plusSeconds(15)));
        assertThat(recovered.eventCandidates()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(TransitEventType.ONE_STOP_AFTER);
            assertThat(event.trackingCycleId()).isEqualTo(TRACKING_CYCLE_ID);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    @DisplayName("Provider 시각이 잠시 누락되어도 마지막 유효 시각보다 오래된 직접 도착 관측으로 우회하지 못한다")
    void audit_rejects_time_regression_across_null_observation(int providerSeconds) {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        TransitObservation before = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "earlier-stop", 5,
                ArrivalEvidence.MOVING, NOW, NOW);
        BusAlarmEvaluationState state = evaluate(alarm, BusAlarmEvaluationState.initial(), before).nextState();
        TransitObservation withoutTime = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10,
                ArrivalEvidence.MOVING, NOW.plusSeconds(10), null);
        BusAlarmEvaluationResult missingTime = evaluate(alarm, state, withoutTime);
        assertThat(missingTime.eventCandidates()).extracting(event -> event.type())
                .containsExactly(TransitEventType.ONE_STOP_BEFORE);
        assertThat(missingTime.nextState().trackedVehicles().get("vehicle").lastObservation()).isEqualTo(withoutTime);
        assertThat(withoutTime.providerDataTime()).isNull();

        BusAlarmEvaluationResult rejected = evaluate(alarm, missingTime.nextState(),
                timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20,
                        ArrivalEvidence.ARRIVED, NOW.plusSeconds(20), NOW.plusSeconds(providerSeconds)));

        assertThat(rejected.eventCandidates()).isEmpty();
        assertThat(rejected.nextState().trackedVehicles().get("vehicle").lastObservation()).isEqualTo(withoutTime);
        BusAlarmEvaluationResult recovered = evaluate(alarm, rejected.nextState(),
                timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20,
                        ArrivalEvidence.ARRIVED, NOW.plusSeconds(30), NOW.plusSeconds(25)));
        assertThat(recovered.eventCandidates()).extracting(event -> event.type()).containsExactly(TransitEventType.ARRIVED);
    }

    @Test
    @DisplayName("같은 위치에서도 동일 Provider 시각의 도착 신호 변경은 새 근거가 아니며 시각이 증가하면 서울 직접 도착을 허용한다")
    void audit_rejects_changed_arrival_evidence_at_same_provider_time() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        TransitObservation before = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10,
                ArrivalEvidence.MOVING, NOW, NOW);
        BusAlarmEvaluationState state = evaluate(alarm, BusAlarmEvaluationState.initial(), before).nextState();
        TransitObservation moving = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20,
                ArrivalEvidence.MOVING, NOW.plusSeconds(10), NOW.plusSeconds(5));
        state = evaluate(alarm, state, moving).nextState();

        BusAlarmEvaluationResult rejected = evaluate(alarm, state,
                timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20,
                        ArrivalEvidence.ARRIVED, NOW.plusSeconds(20), NOW.plusSeconds(5)));

        assertThat(rejected.eventCandidates()).isEmpty();
        assertThat(rejected.nextState().trackedVehicles().get("vehicle").lastObservation()).isEqualTo(moving);
        assertThat(evaluate(alarm, rejected.nextState(),
                timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20,
                        ArrivalEvidence.ARRIVED, NOW.plusSeconds(30), NOW.plusSeconds(25))).eventCandidates())
                .extracting(event -> event.type()).containsExactly(TransitEventType.ARRIVED);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "0, 10, 8, true", "0, 0, 8, false", "0, -5, 8, false", "0, 10, 4, false",
            "0, 0, 5, false", "0, 10, 5, true", "NULL, NULL, 8, true", "NULL, 10, 8, true",
            "0, NULL, 8, true", "NULL, NULL, 5, true"
    }, nullValues = "NULL")
    @DisplayName("정류장 순서와 실제 Provider 시각을 함께 검증하며 시각 누락은 기존 수신 순서에 따라 허용한다")
    void audit_validates_order_and_time_matrix(Integer previousSeconds, Integer currentSeconds, int order,
            boolean accepted) {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        TransitObservation previous = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "earlier-stop", 5,
                ArrivalEvidence.MOVING, NOW, previousSeconds == null ? null : NOW.plusSeconds(previousSeconds));
        BusAlarmEvaluationState state = evaluate(alarm, BusAlarmEvaluationState.initial(), previous).nextState();
        TransitObservation current = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "earlier-stop", order,
                ArrivalEvidence.MOVING, NOW.plusSeconds(20), currentSeconds == null ? null : NOW.plusSeconds(currentSeconds));

        BusAlarmEvaluationResult result = evaluate(alarm, state, current);

        assertThat(result.eventCandidates()).isEmpty();
        VehicleTrackingState tracking = result.nextState().trackedVehicles().get("vehicle");
        assertThat(tracking.lastObservation()).isEqualTo(accepted ? current : previous);
        assertThat(tracking.lastSeenAt()).isEqualTo(current.observedAt());
        assertThat(tracking.trackingCycleId()).isEqualTo(state.trackedVehicles().get("vehicle").trackingCycleId());
        assertThat(tracking.hasObservedBeforeTarget()).isTrue();
        assertThat(tracking.emittedEventTypes()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0, 70", "70, 0", "71, 70", "70, 71"})
    @DisplayName("수신 또는 Provider 시각이 freshness를 벗어나거나 미래이면 위치와 이벤트 이력을 유지한다")
    void audit_preserves_tracking_for_stale_or_future_time(int observedSeconds, int providerSeconds) {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        TransitObservation previous = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10,
                ArrivalEvidence.MOVING, NOW, NOW);
        BusAlarmEvaluationState state = evaluate(alarm, BusAlarmEvaluationState.initial(), previous).nextState();

        BusAlarmEvaluationResult result = evaluate(alarm, state,
                timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20,
                        ArrivalEvidence.ARRIVED, NOW.plusSeconds(observedSeconds), NOW.plusSeconds(providerSeconds)),
                NOW.plusSeconds(70));

        assertThat(result.eventCandidates()).isEmpty();
        assertThat(result.nextState().trackedVehicles().get("vehicle"))
                .isEqualTo(state.trackedVehicles().get("vehicle").observe(previous, NOW.plusSeconds(70)));
    }

    @ParameterizedTest
    @CsvSource(value = {"-1, 5", "0, NULL", "-1, NULL"}, nullValues = "NULL")
    @DisplayName("시각이 없는 관측도 동일하거나 역행한 수신시각으로 정류장 진행을 주장할 수 없다")
    void audit_rejects_non_advancing_receive_time(int observedSeconds, Integer providerSeconds) {
        Alarm alarm = activeAlarm(TransitProvider.TAGO);
        TransitObservation previous = timedObservation(TransitProvider.TAGO, "vehicle", "predecessor", 10,
                ArrivalEvidence.UNAVAILABLE, NOW, null);
        BusAlarmEvaluationState state = evaluate(alarm, BusAlarmEvaluationState.initial(), previous).nextState();

        BusAlarmEvaluationResult result = evaluate(alarm, state,
                timedObservation(TransitProvider.TAGO, "vehicle", "target", 20, ArrivalEvidence.UNAVAILABLE,
                        NOW.plusSeconds(observedSeconds), providerSeconds == null ? null : NOW.plusSeconds(providerSeconds)),
                NOW.plusSeconds(10));

        assertThat(result.eventCandidates()).isEmpty();
        assertThat(result.nextState().trackedVehicles().get("vehicle").lastObservation()).isEqualTo(previous);
    }

    @ParameterizedTest
    @CsvSource({"TAGO, UNAVAILABLE", "SEOUL_BUS, ARRIVED"})
    @DisplayName("Provider 시각이 없는 정상 진행도 기존 Provider별 ARRIVED와 동일 cycle을 유지한다")
    void audit_keeps_arrival_compatibility_without_provider_time(TransitProvider provider, ArrivalEvidence evidence) {
        Alarm alarm = activeAlarm(provider);
        BusAlarmEvaluationState state = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(provider, "vehicle", "predecessor", 10, ArrivalEvidence.UNAVAILABLE, NOW)).nextState();

        BusAlarmEvaluationResult result = evaluate(alarm, state,
                observation(provider, "vehicle", "target", 20, evidence, NOW.plusSeconds(10)));

        assertThat(result.eventCandidates()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(TransitEventType.ARRIVED);
            assertThat(event.trackingCycleId()).isEqualTo(state.trackedVehicles().get("vehicle").trackingCycleId());
            assertThat(event.providerDataTime()).isNull();
        });
    }

    @ParameterizedTest
    @CsvSource({"TAGO, UNAVAILABLE", "SEOUL_BUS, ARRIVED"})
    @DisplayName("이전 추적이 없는 최초 및 신규 차량은 freshness 내 유효한 기존 Baseline 도착 근거를 그대로 사용한다")
    void audit_keeps_direct_baseline_and_new_vehicle_arrival(TransitProvider provider, ArrivalEvidence evidence) {
        Alarm alarm = activeAlarm(provider);
        TransitObservation atTarget = timedObservation(provider, "vehicle", "target", 20, evidence,
                NOW, NOW.minusSeconds(60));

        BusAlarmEvaluationResult baseline = evaluate(alarm, BusAlarmEvaluationState.initial(), atTarget);
        BusAlarmEvaluationState established = evaluate(alarm, BusAlarmEvaluationState.initial(), List.of(), NOW).nextState();
        BusAlarmEvaluationResult newVehicle = evaluate(alarm, established, atTarget);

        assertThat(baseline.eventCandidates()).extracting(event -> event.type()).containsExactly(TransitEventType.ARRIVED);
        assertThat(newVehicle.eventCandidates()).extracting(event -> event.type()).containsExactly(TransitEventType.ARRIVED);
    }

    @Test
    @DisplayName("Snapshot 안의 동일 차량 관측 충돌은 다른 차량 평가와 기존 cycle에 영향을 주지 않는다")
    void audit_isolates_duplicate_vehicle_observations() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        TransitObservation previous = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10,
                ArrivalEvidence.MOVING, NOW, NOW);
        TransitObservation other = timedObservation(TransitProvider.SEOUL_BUS, "other", "predecessor", 10,
                ArrivalEvidence.MOVING, NOW, NOW);
        BusAlarmEvaluationState state = evaluate(alarm, BusAlarmEvaluationState.initial(), List.of(previous, other), NOW)
                .nextState();
        TransitObservation atTarget = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20,
                ArrivalEvidence.ARRIVED, NOW.plusSeconds(10), NOW.plusSeconds(5));
        TransitObservation afterTarget = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "later-stop", 90,
                ArrivalEvidence.MOVING, NOW.plusSeconds(10), NOW.plusSeconds(5));
        TransitObservation otherArrived = timedObservation(TransitProvider.SEOUL_BUS, "other", "target", 20,
                ArrivalEvidence.ARRIVED, NOW.plusSeconds(10), NOW.plusSeconds(5));

        BusAlarmEvaluationResult result = evaluate(alarm, state, List.of(atTarget, afterTarget, otherArrived),
                NOW.plusSeconds(10));

        assertThat(result.eventCandidates()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(TransitEventType.ARRIVED);
            assertThat(event.vehicleTrackingId()).isEqualTo("other");
        });
        assertThat(result.nextState().trackedVehicles().get("vehicle"))
                .isEqualTo(state.trackedVehicles().get("vehicle").observe(previous, NOW.plusSeconds(10)));
        assertThat(evaluate(alarm, result.nextState(), atTarget).eventCandidates())
                .extracting(event -> event.type()).containsExactly(TransitEventType.ARRIVED);
        assertThat(evaluate(alarm, BusAlarmEvaluationState.initial(), List.of(atTarget, afterTarget), NOW.plusSeconds(10))
                .nextState().trackedVehicles()).isEmpty();
    }

    @Test
    @DisplayName("FOLLOW_UP 복원은 현재 관측과 원래 UUID를 사용하고 중복 Snapshot과 후속 이벤트 반복은 차단한다")
    void audit_preserves_follow_up_recovery_and_deduplication() {
        Alarm alarm = followUpAlarm();
        TransitObservation atSuccessor = timedObservation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "successor", 35,
                ArrivalEvidence.MOVING, NOW.plusSeconds(10), NOW.plusSeconds(5));
        TransitObservation conflicting = timedObservation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "later-stop", 90,
                ArrivalEvidence.MOVING, NOW.plusSeconds(10), NOW.plusSeconds(5));
        BusAlarmEvaluationResult ambiguous = evaluate(alarm, BusAlarmEvaluationState.initial(),
                List.of(atSuccessor, conflicting), NOW.plusSeconds(10));
        assertThat(ambiguous.eventCandidates()).isEmpty();
        assertThat(ambiguous.nextState().followUpVehicles()).isEmpty();

        BusAlarmEvaluationResult restored = evaluate(alarm, ambiguous.nextState(), atSuccessor);

        assertThat(restored.eventCandidates()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(TransitEventType.ONE_STOP_AFTER);
            assertThat(event.trackingCycleId()).isEqualTo(TRACKING_CYCLE_ID);
        });
        assertThat(evaluate(alarm, restored.nextState(),
                timedObservation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "later-stop", 90,
                        ArrivalEvidence.MOVING, NOW.plusSeconds(20), NOW.plusSeconds(15))).eventCandidates()).isEmpty();
    }

    @Test
    @DisplayName("ARRIVED 상태의 FOLLOW_UP 이관 후에도 NULL 관측이 마지막 유효 Provider 시각을 지우지 않는다")
    void audit_retains_provider_time_across_follow_up_transfer() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        TransitObservation atTarget = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20,
                ArrivalEvidence.ARRIVED, NOW, NOW);
        BusAlarmEvaluationResult arrived = evaluate(alarm, BusAlarmEvaluationState.initial(), atTarget);
        UUID cycleId = arrived.eventCandidates().getFirst().trackingCycleId();
        LocalDateTime start = LocalDateTime.of(2026, 9, 23, 3, 0);
        alarm.startFollowUp("vehicle", cycleId, start, start.plusMinutes(5));
        BusAlarmEvaluationState state = arrived.nextState().forFollowUp("vehicle");
        TransitObservation withoutTime = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20,
                ArrivalEvidence.MOVING, NOW.plusSeconds(10), null);
        state = evaluate(alarm, state, withoutTime).nextState();
        assertThat(state.followUpVehicles().get("vehicle").lastObservation()).isEqualTo(withoutTime);
        assertThat(state.followUpVehicles().get("vehicle").lastKnownProviderDataTime()).isEqualTo(NOW);

        BusAlarmEvaluationResult rejected = evaluate(alarm, state,
                timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "successor", 35,
                        ArrivalEvidence.MOVING, NOW.plusSeconds(20), NOW.minusSeconds(5)));

        assertThat(rejected.eventCandidates()).isEmpty();
        assertThat(rejected.nextState().followUpVehicles().get("vehicle").lastObservation()).isEqualTo(withoutTime);
        assertThat(evaluate(alarm, rejected.nextState(),
                timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "successor", 35,
                        ArrivalEvidence.MOVING, NOW.plusSeconds(30), NOW.plusSeconds(25))).eventCandidates())
                .singleElement().satisfies(event -> {
                    assertThat(event.type()).isEqualTo(TransitEventType.ONE_STOP_AFTER);
                    assertThat(event.trackingCycleId()).isEqualTo(cycleId);
                });
    }

    @Test
    @DisplayName("오래된 상세 관측만 반복되어도 roster presence는 유지하고 실제 부재 이후에만 새 cycle을 허용한다")
    void audit_separates_stale_position_from_vehicle_presence() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        TransitObservation previous = timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10,
                ArrivalEvidence.MOVING, NOW, NOW);
        BusAlarmEvaluationState state = evaluate(alarm, BusAlarmEvaluationState.initial(), previous).nextState();
        VehicleTrackingState original = state.trackedVehicles().get("vehicle");
        for (int seconds : new int[] {61, 122}) {
            BusAlarmEvaluationResult stale = evaluate(alarm, state,
                    timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "target", 20, ArrivalEvidence.ARRIVED,
                            NOW.plusSeconds(seconds), NOW), NOW.plusSeconds(seconds));
            assertThat(stale.eventCandidates()).isEmpty();
            assertThat(stale.nextState().trackedVehicles().get("vehicle"))
                    .isEqualTo(original.observe(previous, NOW.plusSeconds(seconds)));
            state = stale.nextState();
        }
        BusAlarmEvaluationResult absent = evaluate(alarm, state, List.of(), NOW.plusSeconds(183));
        assertThat(absent.nextState().trackedVehicles()).isEmpty();
        BusAlarmEvaluationResult returned = evaluate(alarm, absent.nextState(),
                timedObservation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING,
                        NOW.plusSeconds(184), NOW.plusSeconds(180)));
        assertThat(returned.nextState().trackedVehicles().get("vehicle").trackingCycleId())
                .isNotEqualTo(original.trackingCycleId());
    }

    @Test
    @DisplayName("FOLLOW_UP의 정류장 충돌 관측은 검증된 위치와 Provider 시각을 오염시키지 않아 이후 정상 도달을 막지 않는다")
    void audit_preserves_follow_up_time_for_stop_conflict() {
        Alarm alarm = followUpAlarm();
        TransitObservation atTarget = timedObservation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "target", 20,
                ArrivalEvidence.ARRIVED, NOW, NOW);
        BusAlarmEvaluationState state = evaluate(alarm, BusAlarmEvaluationState.initial(), atTarget).nextState();

        BusAlarmEvaluationResult conflict = evaluate(alarm, state,
                timedObservation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "different-stop", 35,
                        ArrivalEvidence.MOVING, NOW.plusSeconds(20), NOW.plusSeconds(15)));

        assertThat(conflict.eventCandidates()).isEmpty();
        assertThat(conflict.nextState().followUpVehicles().get("follow-up-vehicle"))
                .isEqualTo(state.followUpVehicles().get("follow-up-vehicle").observe(atTarget, NOW.plusSeconds(20)));
        BusAlarmEvaluationResult recovered = evaluate(alarm, conflict.nextState(),
                timedObservation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "successor", 35,
                        ArrivalEvidence.MOVING, NOW.plusSeconds(30), NOW.plusSeconds(10)));
        assertThat(recovered.eventCandidates()).extracting(event -> event.type()).containsExactly(TransitEventType.ONE_STOP_AFTER);
    }

    @ParameterizedTest
    @CsvSource({"37.0008992, true", "37.0008994, false"})
    @DisplayName("TAGO baseline 도착은 100m 양쪽 경계에서 GPS 근거를 구분한다")
    void checks_tago_gps_distance_boundary(String latitude, boolean arrived) {
        BusAlarmEvaluationResult result = evaluate(activeAlarm(TransitProvider.TAGO), BusAlarmEvaluationState.initial(),
                tagoObservation("vehicle", "target", 20, new BigDecimal(latitude), new BigDecimal("127"), NOW));

        assertThat(result.eventCandidates()).hasSize(arrived ? 1 : 0);
        if (arrived) {
            assertThat(result.eventCandidates().getFirst().type()).isEqualTo(TransitEventType.ARRIVED);
        }
    }

    @Test
    @DisplayName("방향 충돌은 도착과 통과를 막고 원래 방향의 정상 관측으로 같은 cycle을 복구한다")
    void rejects_direction_conflict_and_recovers_arrival() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        TransitObservation previous = directedObservation("predecessor", 10, ArrivalEvidence.MOVING, "outbound", NOW);
        BusAlarmEvaluationState state = evaluate(alarm, BusAlarmEvaluationState.initial(), previous).nextState();
        UUID cycle = state.trackedVehicles().get("vehicle").trackingCycleId();
        BusAlarmEvaluationResult conflict = evaluate(alarm, state,
                directedObservation("target", 20, ArrivalEvidence.ARRIVED, "inbound", NOW.plusSeconds(1)));
        assertThat(conflict.eventCandidates()).isEmpty();
        assertThat(conflict.nextState().trackedVehicles().get("vehicle").lastObservation()).isEqualTo(previous);
        BusAlarmEvaluationResult passedConflict = evaluate(alarm, conflict.nextState(),
                directedObservation("later", 90, ArrivalEvidence.MOVING, "inbound", NOW.plusSeconds(2)));
        assertThat(passedConflict.eventCandidates()).isEmpty();
        BusAlarmEvaluationResult recovered = evaluate(alarm, passedConflict.nextState(),
                directedObservation("target", 20, ArrivalEvidence.ARRIVED, "outbound", NOW.plusSeconds(3)));
        assertThat(recovered.eventCandidates()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(TransitEventType.ARRIVED);
            assertThat(event.trackingCycleId()).isEqualTo(cycle);
        });
    }

    @ParameterizedTest
    @CsvSource({"299, false", "300, true", "301, true"})
    @DisplayName("FOLLOW_UP은 만료 직전까지 후속 도착을 허용하고 정확한 만료 시각부터 차단한다")
    void checks_follow_up_expiry_boundary(int seconds, boolean expired) {
        Instant now = NOW.plusSeconds(seconds);
        BusAlarmEvaluationResult result = evaluate(followUpAlarm(), BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "follow-up-vehicle", "successor", 35,
                        ArrivalEvidence.MOVING, now), now);

        assertThat(result.followUpExpired()).isEqualTo(expired);
        assertThat(result.eventCandidates()).hasSize(expired ? 0 : 1);
        if (!expired) {
            assertThat(result.eventCandidates().getFirst().type()).isEqualTo(TransitEventType.ONE_STOP_AFTER);
            assertThat(result.eventCandidates().getFirst().trackingCycleId()).isEqualTo(TRACKING_CYCLE_ID);
        }
    }

    @Test
    @DisplayName("ACTIVE 메모리 초기화 뒤 target 이후 차량은 새 baseline으로 처리하여 통과를 만들지 않는다")
    void reset_active_memory_does_not_invent_passed() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        BusAlarmEvaluationState tracked = evaluate(alarm, BusAlarmEvaluationState.initial(),
                observation(TransitProvider.SEOUL_BUS, "vehicle", "predecessor", 10, ArrivalEvidence.MOVING, NOW))
                .nextState();
        TransitObservation after = observation(TransitProvider.SEOUL_BUS, "vehicle", "later", 90,
                ArrivalEvidence.MOVING, NOW.plusSeconds(1));

        assertThat(evaluate(alarm, tracked, after).eventCandidates()).extracting(event -> event.type())
                .containsExactly(TransitEventType.PASSED);
        BusAlarmEvaluationResult reset = evaluate(alarm, BusAlarmEvaluationState.initial(), after);
        assertThat(reset.eventCandidates()).isEmpty();
        assertThat(reset.nextState().baselineAfterVehicles()).containsKey("vehicle");
    }

    private static TransitObservation directedObservation(String stopId, int order, ArrivalEvidence evidence,
            String direction, Instant observedAt) {
        return new TransitObservation(TransitProvider.SEOUL_BUS, "route", "vehicle", stopId, order, null,
                direction, null, new BigDecimal("37"), new BigDecimal("127"), observedAt, null, evidence);
    }

    private static TransitObservation timedObservation(TransitProvider provider, String vehicleId, String stopId,
            int stopOrder, ArrivalEvidence evidence, Instant observedAt, Instant providerDataTime) {
        TransitObservation observation = observation(provider, vehicleId, stopId, stopOrder, evidence, observedAt);
        return new TransitObservation(provider, observation.externalRouteId(), vehicleId, stopId, stopOrder,
                observation.currentStopName(), observation.directionContext(), observation.sectionContext(),
                observation.latitude(), observation.longitude(), observedAt, providerDataTime, evidence);
    }

    private BusAlarmEvaluationResult evaluate(Alarm alarm, BusAlarmEvaluationState state, TransitObservation observation) {
        return evaluate(alarm, state, List.of(observation), observation.observedAt());
    }

    private BusAlarmEvaluationResult evaluate(
            Alarm alarm,
            BusAlarmEvaluationState state,
            TransitObservation observation,
            Instant now
    ) {
        return evaluate(alarm, state, List.of(observation), now);
    }

    private BusAlarmEvaluationResult evaluate(
            Alarm alarm,
            BusAlarmEvaluationState state,
            List<TransitObservation> observations,
            Instant now
    ) {
        return evaluator.evaluate(alarm, state, observations, now);
    }

    private BusAlarmEvaluationResult evaluatePresent(
            Alarm alarm,
            BusAlarmEvaluationState state,
            Set<String> presentVehicleIds,
            List<TransitObservation> observations,
            Instant now
    ) {
        return evaluator.evaluate(alarm, state, presentVehicleIds, observations, now);
    }

    private static Alarm activeAlarm(TransitProvider provider) {
        Alarm alarm = new Alarm(null, target(provider));
        alarm.activate();
        return alarm;
    }

    private static Alarm followUpAlarm() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        LocalDateTime start = LocalDateTime.of(2026, 9, 23, 3, 0);
        alarm.startFollowUp("follow-up-vehicle", TRACKING_CYCLE_ID, start, start.plusMinutes(5));
        return alarm;
    }

    private static BusAlarmTarget target(TransitProvider provider) {
        return new BusAlarmTarget(
                provider, "route", "target", 20, "7000", "Target", new BigDecimal("37.0000000"),
                new BigDecimal("127.0000000"), provider == TransitProvider.TAGO ? "31010" : null,
                new AdjacentStopSnapshot("predecessor", 10), new AdjacentStopSnapshot("successor", 35)
        );
    }

    private static TransitObservation observation(
            TransitProvider provider,
            String vehicleId,
            String stopId,
            int stopOrder,
            ArrivalEvidence arrivalEvidence,
            Instant observedAt
    ) {
        return new TransitObservation(
                provider, "route", vehicleId, stopId, stopOrder, null, null, null,
                new BigDecimal("37.0000000"), new BigDecimal("127.0000000"), observedAt, null, arrivalEvidence
        );
    }

    private static TransitObservation tagoObservation(
            String vehicleId,
            String stopId,
            int stopOrder,
            BigDecimal latitude,
            BigDecimal longitude,
            Instant observedAt
    ) {
        return new TransitObservation(
                TransitProvider.TAGO, "route", vehicleId, stopId, stopOrder, null, null, null,
                latitude, longitude, observedAt, null, ArrivalEvidence.UNAVAILABLE
        );
    }
}
