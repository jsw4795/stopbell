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

class BusAlarmEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-09-23T03:00:00Z");

    private final BusRouteTraversalService routeTraversalService = mock(BusRouteTraversalService.class);
    private final BusAlarmEvaluator evaluator = new BusAlarmEvaluator(routeTraversalService);

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

    private static Alarm activeAlarm(TransitProvider provider) {
        Alarm alarm = new Alarm(null, target(provider));
        alarm.activate();
        return alarm;
    }

    private static Alarm followUpAlarm() {
        Alarm alarm = activeAlarm(TransitProvider.SEOUL_BUS);
        LocalDateTime start = LocalDateTime.of(2026, 9, 23, 3, 0);
        alarm.startFollowUp("follow-up-vehicle", start, start.plusMinutes(5));
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
