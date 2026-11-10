package com.stopbell.boarding.service;

import static com.stopbell.boarding.BoardingSessionFixtures.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.stopbell.boarding.domain.*;
import com.stopbell.boarding.domain.BoardingProgressObservation.Meaning;
import com.stopbell.boarding.domain.BoardingTrackingDecision.Outcome;
import com.stopbell.boarding.entity.*;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardingProgressEvaluatorTest {
    private final BoardingProgressEvaluator evaluator = new BoardingProgressEvaluator(Duration.ofSeconds(30));
    static final List<Integer> ORDERS = List.of(10, 20, 40, 80, 100);

    @Test
    @DisplayName("오래된 제공시각 자체는 정확한 진행 관측을 차단하지 않는다")
    void accepts_older_provider_time_for_forward_progress() {
        var session = snapshot(0);
        var input = observation(session, 1, Meaning.VERIFIED_REACHED, 20);
        var vehicle = input.vehicle();
        var older = new Vehicle(vehicle.id(), vehicle.number(), vehicle.stopId(), vehicle.order(), 0,
                vehicle.observedAt(), NOW.minusSeconds(90), null, null);
        assertThat(evaluator.evaluate(session, replace(input, older), UTC_NOW.plusSeconds(20)).outcome())
                .isEqualTo(Outcome.PREPARE_ALIGHT);
    }

    @Test
    @DisplayName("이전 위치 재수신과 긴 관측 공백도 정확히 대응하면 평가한다")
    void accepts_previous_position_and_long_gap() {
        var session = snapshot(1);
        assertThat(evaluator.evaluate(session, observation(session, 0, Meaning.VERIFIED_REACHED, 20),
                UTC_NOW.plusSeconds(20)).outcome()).isEqualTo(Outcome.NO_CANDIDATE);
        assertThat(evaluator.evaluate(session, observation(session, 2, Meaning.VERIFIED_REACHED, 121),
                UTC_NOW.plusSeconds(121)).outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
    }

    @Test
    @DisplayName("이전 기준점도 목적지 이후여도 현재 간격으로 통과를 판단한다")
    void passed_gap_does_not_depend_on_previous_index() {
        var session = snapshot(4);
        var result = evaluator.evaluate(session, observation(session, 4, Meaning.VERIFIED_REACHED, 20),
                UTC_NOW.plusSeconds(20));
        assertThat(result.outcome()).isEqualTo(Outcome.PASSED_OBSERVED);
    }

    @Test
    void tago_repeated_receipts_preserve_continuity_without_creating_progress() {
        var session = tagoSnapshot(0, BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE, UTC_NOW);
        for (int seconds = 20; seconds <= 180; seconds += 20) {
            var result = evaluator.evaluate(session, tagoObservation(session, 0, seconds), UTC_NOW.plusSeconds(seconds));
            assertThat(result.outcome()).isEqualTo(Outcome.NO_CANDIDATE);
            assertThat(result.checkpoint().getEvidence()).isEqualTo(BoardingCheckpoint.Evidence.ID_ORDER);
            assertThat(result.checkpoint().getObservedAt()).isEqualTo(UTC_NOW.plusSeconds(seconds));
            session = tagoSnapshot(0, result.checkpoint().getEvidence(), result.checkpoint().getObservedAt());
        }
        var advanced = evaluator.evaluate(session, tagoObservation(session, 1, 200), UTC_NOW.plusSeconds(200));
        assertThat(advanced.outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
        assertThat(advanced.checkpoint().getEvidence()).isEqualTo(BoardingCheckpoint.Evidence.ID_ORDER);
        assertThat(advanced.checkpoint().getProviderDataTime()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"1,0,NO_CANDIDATE", "0,3,ARRIVAL_OBSERVED"})
    void tago_previous_and_unverified_destination_use_current_gap(int previous, int next, Outcome expected) {
        var session = tagoSnapshot(previous, BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE, UTC_NOW);
        var result = evaluator.evaluate(session, tagoObservation(session, next, 20), UTC_NOW.plusSeconds(20));
        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.checkpoint().getIndex()).isEqualTo(next);
    }

    @ParameterizedTest
    @CsvSource({"1,PREPARE_ALIGHT", "2,PREPARE_ALIGHT", "3,ARRIVAL_OBSERVED", "4,PASSED_OBSERVED"})
    void tago_verified_progress_uses_common_decisions(int index, Outcome outcome) {
        var session = tagoSnapshot(0, BoardingCheckpoint.Evidence.ID_ORDER, UTC_NOW);
        assertThat(evaluator.evaluate(session, tagoObservation(session, index, 20), UTC_NOW.plusSeconds(20)).outcome())
                .isEqualTo(outcome);
    }

    private static BoardingTrackingSnapshot tagoSnapshot(int index, BoardingCheckpoint.Evidence evidence, LocalDateTime time) {
        var original = snapshot(index);
        var traversal = new BoardingTraversalSnapshot(1, TransitProvider.TAGO, original.routeId(), "31010",
                original.traversal().occurrences(), 3, BoardingTraversalSnapshot.AlightAvailability.UNKNOWN, null);
        var stop = traversal.occurrences().get(index);
        return new BoardingTrackingSnapshot(1L, 0, BoardingSessionStatus.TRACKING,
                evidence == BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE ? BoardingStateReason.BASELINE_UNVERIFIED : null,
                TransitProvider.TAGO, original.routeId(), "31010", NUMBER, NUMBER, traversal,
                new BoardingCheckpoint(stop.externalStopId(), stop.order(), index, evidence, null, time, null),
                UTC_NOW.plusHours(4), true, 2);
    }

    private static BoardingProgressObservation tagoObservation(BoardingTrackingSnapshot session, int index, int seconds) {
        var stop = session.traversal().occurrences().get(index);
        return new BoardingProgressObservation(TransitProvider.TAGO, session.routeId(), session.cityCode(),
                new Vehicle(NUMBER, NUMBER, stop.externalStopId(), stop.order(), null, NOW.plusSeconds(seconds), null, null, null),
                Meaning.VERIFIED_REACHED, false, null, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void tago_order_only_and_id_observations_can_alternate(boolean initialHasId) {
        var base = tagoSnapshot(0, BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE, UTC_NOW);
        var first = new BoardingCheckpoint(initialHasId ? "s0" : null, ORDERS.get(0), 0,
                BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE, null, UTC_NOW, null);
        var session = new BoardingTrackingSnapshot(base.id(), 0, base.status(), base.reason(), base.provider(),
                base.routeId(), base.cityCode(), base.vehicleId(), base.vehicleNumber(), base.traversal(), first,
                base.expiresAt(), true, 2);
        var next = tagoObservation(session, 1, 20);
        var v = next.vehicle();
        var mixed = new BoardingProgressObservation(base.provider(), base.routeId(), base.cityCode(),
                new Vehicle(v.id(), v.number(), initialHasId ? null : v.stopId(), v.order(), null, v.observedAt(), null, null, null),
                Meaning.VERIFIED_REACHED, initialHasId, null, false);
        var result = evaluator.evaluate(session, mixed, UTC_NOW.plusSeconds(20));
        assertThat(result.outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
        assertThat(result.checkpoint().getEvidence()).isEqualTo(initialHasId
                ? BoardingCheckpoint.Evidence.VERIFIED_ORDER_ONLY : BoardingCheckpoint.Evidence.ID_ORDER);
        assertThat(result.checkpoint().getStopExternalId()).isEqualTo(initialHasId ? null : "s1");
    }

    @Test
    void tago_wrong_id_and_unknown_order_are_held_without_alert() {
        var session = tagoSnapshot(0, BoardingCheckpoint.Evidence.ID_ORDER, UTC_NOW);
        var observation = tagoObservation(session, 1, 20);
        var vehicle = observation.vehicle();
        for (var invalid : List.of(
                new Vehicle(NUMBER, NUMBER, "wrong", vehicle.order(), null, vehicle.observedAt(), null, null, null),
                new Vehicle(NUMBER, NUMBER, null, 999, null, vehicle.observedAt(), null, null, null))) {
            var input = new BoardingProgressObservation(TransitProvider.TAGO, session.routeId(), session.cityCode(),
                    invalid, Meaning.VERIFIED_REACHED, invalid.stopId() == null, null, false);
            assertThat(evaluator.evaluate(session, input, UTC_NOW.plusSeconds(20)).outcome()).isEqualTo(Outcome.HELD);
        }
    }

    static BoardingTrackingSnapshot snapshot(int previousIndex) {
        var traversal = new BoardingTraversalSnapshot(1, TransitProvider.SEOUL_BUS, "route-id", null,
                java.util.stream.IntStream.range(0, 5).mapToObj(index -> new BoardingTraversalSnapshot.Occurrence(
                        index + 11L, "s" + index, ORDERS.get(index))).toList(),
                3, BoardingTraversalSnapshot.AlightAvailability.ALLOWED, "controlled-test-evidence");
        return snapshot(traversal, previousIndex, BoardingSessionStatus.TRACKING, null);
    }

    static BoardingTrackingSnapshot snapshot(BoardingTraversalSnapshot traversal, int previousIndex,
            BoardingSessionStatus status, BoardingStateReason reason) {
        var previous = traversal.occurrences().get(previousIndex);
        return new BoardingTrackingSnapshot(1L, 0, status, reason, traversal.provider(), traversal.externalRouteId(),
                traversal.cityCode(), "vehicle-id", NUMBER, traversal, new BoardingCheckpoint(previous.externalStopId(),
                    previous.order(), previousIndex, BoardingCheckpoint.Evidence.ID_ORDER, null, UTC_NOW, UTC_NOW.minusSeconds(1)),
                UTC_NOW.plusHours(4));
    }

    static BoardingProgressObservation observation(BoardingTrackingSnapshot session, int index, Meaning meaning, long seconds) {
        var stop = session.traversal().occurrences().get(index);
        return new BoardingProgressObservation(session.provider(), session.routeId(), session.cityCode(),
                new Vehicle("vehicle-id", NUMBER, stop.externalStopId(), stop.order(), meaning == Meaning.DIRECT_ARRIVAL ? 1 : 0,
                        NOW.plusSeconds(seconds), NOW.plusSeconds(seconds - 1), null, null), meaning, false, null, false);
    }

    @ParameterizedTest
    @CsvSource({"2,1,PREPARE_ALIGHT", "2,2,PREPARE_ALIGHT", "1,1,NO_CANDIDATE", "1,2,PREPARE_ALIGHT",
            "2,3,ARRIVAL_OBSERVED", "1,3,ARRIVAL_OBSERVED", "2,4,PASSED_OBSERVED", "1,4,PASSED_OBSERVED"})
    @DisplayName("선택한 occurrence 오프셋과 목적지 이전 여부로 준비·대체 알림을 구분한다")
    void chosen_offset_and_destination_boundary(int offset, int index, Outcome outcome) {
        var base = snapshot(0);
        var session = new BoardingTrackingSnapshot(base.id(), base.revision(), base.status(), base.reason(),
                base.provider(), base.routeId(), base.cityCode(), base.vehicleId(), base.vehicleNumber(),
                base.traversal(), base.checkpoint(), base.expiresAt(), true, offset);
        for (var meaning : List.of(Meaning.DIRECT_ARRIVAL, Meaning.VERIFIED_REACHED)) {
            var result = evaluator.evaluate(session, observation(session, index, meaning, 20), UTC_NOW.plusSeconds(20));
            assertThat(result.outcome()).isEqualTo(outcome);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    @DisplayName("최초 위치 미검증 세션도 이동 중 exact occurrence가 목적지 이전이면 현재 준비 시점을 판단한다")
    void first_moving_baseline_near_target(int offset) {
        var base = snapshot(0);
        var checkpoint = new BoardingCheckpoint(null, 0, -1, BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE,
                null, UTC_NOW, UTC_NOW.minusSeconds(1));
        var session = new BoardingTrackingSnapshot(base.id(), base.revision(), base.status(), BoardingStateReason.BASELINE_UNVERIFIED,
                base.provider(), base.routeId(), base.cityCode(), base.vehicleId(), base.vehicleNumber(),
                base.traversal(), checkpoint, base.expiresAt(), true, offset);
        assertThat(evaluator.evaluate(session, observation(session, 2, Meaning.VERIFIED_REACHED, 20),
                UTC_NOW.plusSeconds(20)).outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
        assertThat(evaluator.evaluate(session, observation(session, 3, Meaning.VERIFIED_REACHED, 20),
                UTC_NOW.plusSeconds(20)).outcome()).isEqualTo(Outcome.ARRIVAL_OBSERVED);
        assertThat(evaluator.evaluate(session, observation(session, 4, Meaning.VERIFIED_REACHED, 20),
                UTC_NOW.plusSeconds(20)).outcome()).isEqualTo(Outcome.PASSED_OBSERVED);
    }

    @ParameterizedTest
    @CsvSource({"0,0,NO_CANDIDATE,3", "0,1,PREPARE_ALIGHT,2", "0,2,PREPARE_ALIGHT,1",
            "1,2,PREPARE_ALIGHT,1", "0,3,NO_CANDIDATE,0", "0,4,PASSED_OBSERVED,-1", "2,3,NO_CANDIDATE,0"})
    @DisplayName("검증된 이동 기준점의 배열 간격은 미정차와 경유 occurrence를 포함하고 raw order 차이를 사용하지 않는다")
    void evaluate_progress(int previousIndex, int index, Outcome outcome, int gap) {
        var session = snapshot(previousIndex);
        var result = evaluator.evaluate(session, observation(session, index, Meaning.VERIFIED_REFERENCE, 20), UTC_NOW.plusSeconds(20));
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.remainingOccurrenceGap()).isEqualTo(gap);
        assertThat(result.checkpoint().getIndex()).isEqualTo(index);
        assertThat(result.checkpoint().getObservedAt()).isEqualTo(UTC_NOW.plusSeconds(20));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    @DisplayName("사전 알림 OFF는 준비만 생략하며 정차하지 않은 목적지의 도달·통과 판정을 유지한다")
    void preparation_off_keeps_destination(int index) {
        var base = snapshot(0);
        var session = new BoardingTrackingSnapshot(base.id(), base.revision(), base.status(), base.reason(),
                base.provider(), base.routeId(), base.cityCode(), base.vehicleId(), base.vehicleNumber(),
                base.traversal(), base.checkpoint(), base.expiresAt(), false);
        var result = evaluator.evaluate(session, observation(session, index, Meaning.VERIFIED_REACHED, 20), UTC_NOW.plusSeconds(20));
        assertThat(result.outcome()).isEqualTo(index == 3 ? Outcome.ARRIVAL_OBSERVED
                : index == 4 ? Outcome.PASSED_OBSERVED : Outcome.NO_CANDIDATE);
        assertThat(result.checkpoint()).isNotNull();
    }

    @Test
    @DisplayName("정차 flag가 없어도 목적 occurrence에 도달한 의미가 별도로 검증되면 목적지 알림을 판정한다")
    void reached_without_stopping() {
        var session = snapshot(0);
        var input = observation(session, 3, Meaning.VERIFIED_REACHED, 20);
        assertThat(input.vehicle().stopFlag()).isZero();
        var result = evaluator.evaluate(session, input, UTC_NOW.plusSeconds(20));
        assertThat(result.outcome()).isEqualTo(Outcome.ARRIVAL_OBSERVED);
        assertThat(result.expiresAt()).isEqualTo(UTC_NOW.plusSeconds(50));
    }

    @Test
    @DisplayName("목적 occurrence의 직접 도착 근거로만 ARRIVAL_OBSERVED를 판정하며 준비 알림을 소급하지 않는다")
    void direct_arrival() {
        var session = snapshot(0);
        var result = evaluator.evaluate(session, observation(session, 3, Meaning.DIRECT_ARRIVAL, 20), UTC_NOW.plusSeconds(20));
        assertThat(result.outcome()).isEqualTo(Outcome.ARRIVAL_OBSERVED);
        assertThat(result.reason()).isEqualTo(BoardingStateReason.ARRIVAL_OBSERVED);
    }

    @ParameterizedTest
    @EnumSource(value = BoardingTraversalSnapshot.AlightAvailability.class, names = {"PROHIBITED", "UNKNOWN"})
    @DisplayName("목적 occurrence의 하차가 금지되거나 미확정이어도 검증된 진행으로 준비 알림을 판정한다")
    void track_regardless_of_alight_availability(BoardingTraversalSnapshot.AlightAvailability availability) {
        var base = snapshot(0).traversal();
        var traversal = new BoardingTraversalSnapshot(1, base.provider(), base.externalRouteId(), base.cityCode(),
                base.occurrences(), base.targetIndex(), availability,
                availability == BoardingTraversalSnapshot.AlightAvailability.UNKNOWN ? null : "controlled-test-prohibition");
        var session = snapshot(traversal, 0, BoardingSessionStatus.TRACKING, null);
        assertThat(evaluator.evaluate(session, observation(session, 1, Meaning.DIRECT_ARRIVAL, 20),
                UTC_NOW.plusSeconds(20)).outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    @DisplayName("목적지 또는 이후의 숫자 order라도 진행 의미가 미검증이면 도착·통과로 종료하지 않는다")
    void uncertain_at_or_after_target(int index) {
        var session = snapshot(0);
        assertHeld(evaluator.evaluate(session, observation(session, index, Meaning.UNVERIFIED, 20), UTC_NOW.plusSeconds(20)),
                BoardingStateReason.TRAVERSAL_AMBIGUOUS);
    }

    @ParameterizedTest
    @ValueSource(longs = {30, 259200000000L})
    @DisplayName("준비 TTL은 세션 기한으로 제한하며 큰 유한 설정도 시각 덧셈 overflow를 일으키지 않는다")
    void cap_ttl_to_session_lifetime(long ttlSeconds) {
        var base = snapshot(0);
        var session = new BoardingTrackingSnapshot(base.id(), base.revision(), base.status(), null, base.provider(), base.routeId(),
                base.cityCode(), base.vehicleId(), base.vehicleNumber(), base.traversal(), base.checkpoint(), UTC_NOW.plusSeconds(35));
        var configured = new BoardingProgressEvaluator(Duration.ofSeconds(ttlSeconds));
        var result = configured.evaluate(session, observation(session, 1, Meaning.VERIFIED_REFERENCE, 20), UTC_NOW.plusSeconds(20));
        assertThat(result.outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
        assertThat(result.expiresAt()).isEqualTo(session.expiresAt());
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 31, 60})
    @DisplayName("관측 원래 시각의 준비 TTL 경계 이상에서는 checkpoint만 반영하고 후보는 만료로 판정한다")
    void original_ttl(int age) {
        var session = snapshot(0);
        var observation = observation(session, 1, Meaning.VERIFIED_REFERENCE, 20);
        var result = evaluator.evaluate(session, observation, UTC_NOW.plusSeconds(20 + age));
        assertThat(result.outcome()).isEqualTo(Outcome.EXPIRED_CANDIDATE);
        assertThat(result.expiresAt()).isEqualTo(UTC_NOW.plusSeconds(50));
        assertThat(result.checkpoint()).isNotNull();
    }

    @Test
    @DisplayName("같은 제공시각의 반복 응답도 현재 occurrence의 후보를 계산한다")
    void repeated_source_data() {
        var session = snapshot(1);
        var observation = observation(session, 1, Meaning.VERIFIED_REFERENCE, 0);
        var vehicle = observation.vehicle();
        var receivedAgain = new Vehicle(vehicle.id(), vehicle.number(), vehicle.stopId(), vehicle.order(), 0,
                NOW.plusSeconds(20), vehicle.providerDataTime(), null, null);
        var result = evaluator.evaluate(session, replace(observation, receivedAgain), UTC_NOW.plusSeconds(20));
        assertThat(result.outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
        assertThat(result.checkpoint().getIndex()).isEqualTo(1);
        assertThat(result.observedAt()).isEqualTo(UTC_NOW.plusSeconds(20));
    }

    @Test
    @DisplayName("같은 제공시각이라도 다른 정확한 occurrence를 평가한다")
    void same_time_conflict() {
        var session = snapshot(0);
        var result = evaluator.evaluate(session, observation(session, 1, Meaning.VERIFIED_REFERENCE, 0), UTC_NOW.plusSeconds(20));
        assertThat(result.outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
        assertThat(result.checkpoint().getIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 occurrence의 새 유효 제공시각은 판단 가능하며 durable Event 연결 전에는 후보가 다시 계산될 수 있다")
    void repeated_candidates_are_not_durable_deduplication() {
        var session = snapshot(1);
        var first = evaluator.evaluate(session, observation(session, 1, Meaning.VERIFIED_REFERENCE, 20), UTC_NOW.plusSeconds(20));
        var next = new BoardingTrackingSnapshot(session.id(), 1, session.status(), null, session.provider(), session.routeId(),
                session.cityCode(), session.vehicleId(), session.vehicleNumber(), session.traversal(), first.checkpoint(), session.expiresAt());
        var second = evaluator.evaluate(next, observation(next, 2, Meaning.VERIFIED_REFERENCE, 40), UTC_NOW.plusSeconds(40));
        assertThat(first.outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
        assertThat(second.outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
    }

    @ParameterizedTest
    @CsvSource({"0,TRAVERSAL_AMBIGUOUS", "1,EXPIRED_CANDIDATE", "2,PREPARE_ALIGHT", "3,NO_CANDIDATE",
            "4,PREPARE_ALIGHT", "5,PREPARE_ALIGHT", "6,TRAVERSAL_AMBIGUOUS", "7,PREPARE_ALIGHT",
            "8,IDENTITY_CONFLICT", "9,IDENTITY_CONFLICT", "10,IDENTITY_CONFLICT", "11,IDENTITY_CONFLICT",
            "12,PREPARE_ALIGHT", "13,PREPARE_ALIGHT", "14,TRAVERSAL_AMBIGUOUS", "15,TRAVERSAL_AMBIGUOUS"})
    @DisplayName("정확한 occurrence는 시각과 이전 위치에 관계없이 평가하고 식별 불일치는 보류한다")
    void reject_uncertain_observation(int scenario, String expected) {
        var session = snapshot(1);
        var observation = observation(session, 2, Meaning.VERIFIED_REFERENCE, 20);
        var v = observation.vehicle();
        observation = switch (scenario) {
            case 0 -> new BoardingProgressObservation(session.provider(), session.routeId(), null, v, Meaning.UNVERIFIED, false, null, false);
            case 1 -> replace(observation, new Vehicle(v.id(), v.number(), v.stopId(), v.order(), 0, NOW.minusSeconds(61), NOW.minusSeconds(62), null, null));
            case 2 -> replace(observation, new Vehicle(v.id(), v.number(), v.stopId(), v.order(), 0, NOW.plusSeconds(21), NOW.plusSeconds(20), null, null));
            case 3 -> observation(session, 0, Meaning.VERIFIED_REFERENCE, 20);
            case 4 -> replace(observation, new Vehicle(v.id(), v.number(), v.stopId(), v.order(), 0, v.observedAt(), NOW.minusSeconds(2), null, null));
            case 5 -> observation(session, 2, Meaning.VERIFIED_REFERENCE, 121);
            case 6 -> new BoardingProgressObservation(session.provider(), session.routeId(), null, v, Meaning.VERIFIED_REFERENCE, false, null, true);
            case 7 -> new BoardingProgressObservation(session.provider(), session.routeId(), null, v, Meaning.VERIFIED_REFERENCE, false, "other-direction", false);
            case 8 -> replace(observation, new Vehicle("other-id", v.number(), v.stopId(), v.order(), 0, v.observedAt(), v.providerDataTime(), null, null));
            case 9 -> replace(observation, new Vehicle(v.id(), "비슷한번호1234", v.stopId(), v.order(), 0, v.observedAt(), v.providerDataTime(), null, null));
            case 10 -> new BoardingProgressObservation(TransitProvider.TAGO, session.routeId(), "31010", v, Meaning.VERIFIED_REFERENCE, false, null, false);
            case 11 -> new BoardingProgressObservation(session.provider(), "other-route", null, v, Meaning.VERIFIED_REFERENCE, false, null, false);
            case 12 -> replace(observation, new Vehicle(v.id(), v.number(), v.stopId(), v.order(), 0, v.observedAt(), NOW.plusSeconds(21), null, null));
            case 13 -> replace(observation, new Vehicle(v.id(), v.number(), v.stopId(), v.order(), 0, v.observedAt(), null, null, null));
            case 14 -> replace(observation, new Vehicle(v.id(), v.number(), "wrong-stop", v.order(), 0, v.observedAt(), v.providerDataTime(), null, null));
            default -> replace(observation, new Vehicle(v.id(), v.number(), v.stopId(), 999, 0, v.observedAt(), v.providerDataTime(), null, null));
        };
        var result = evaluator.evaluate(session, observation, UTC_NOW.plusSeconds(scenario == 5 ? 121 : 20));
        if (expected.equals("TRAVERSAL_AMBIGUOUS") || expected.equals("IDENTITY_CONFLICT")) {
            assertHeld(result, BoardingStateReason.valueOf(expected));
        } else {
            assertThat(result.outcome()).isEqualTo(Outcome.valueOf(expected));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("nodeid 없는 order는 검증 근거가 있을 때만 진행에 쓰며 정류장 ID를 합성하지 않는다")
    void order_only(boolean verified) {
        var session = snapshot(0);
        var base = observation(session, 1, Meaning.VERIFIED_REFERENCE, 20);
        var v = base.vehicle();
        var observation = new BoardingProgressObservation(session.provider(), session.routeId(), null,
                new Vehicle(v.id(), v.number(), null, v.order(), 0, v.observedAt(), v.providerDataTime(), null, null),
                Meaning.VERIFIED_REFERENCE, verified, null, false);
        var result = evaluator.evaluate(session, observation, UTC_NOW.plusSeconds(20));
        if (verified) {
            assertThat(result.outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
            assertThat(result.checkpoint().getStopExternalId()).isNull();
            assertThat(result.checkpoint().getEvidence()).isEqualTo(BoardingCheckpoint.Evidence.VERIFIED_ORDER_ONLY);
        } else {
            assertHeld(result, BoardingStateReason.STALE_PROGRESS);
        }
    }

    @Test
    @DisplayName("반복 Stop은 정확한 ID와 서로 다른 order로 occurrence를 구별하며 모호한 관측 쌍은 보류한다")
    void repeated_stop_occurrences() {
        var base = snapshot(0).traversal();
        var traversal = new BoardingTraversalSnapshot(1, base.provider(), base.externalRouteId(), null,
                List.of(new BoardingTraversalSnapshot.Occurrence(11, "repeat", 10),
                    new BoardingTraversalSnapshot.Occurrence(12, "middle", 20),
                    new BoardingTraversalSnapshot.Occurrence(13, "repeat", 40),
                    new BoardingTraversalSnapshot.Occurrence(14, "target", 80)),
                3, base.targetAlightAvailability(), base.targetClassificationEvidence());
        var session = snapshot(traversal, 0, BoardingSessionStatus.TRACKING, null);
        var observation = observation(session, 2, Meaning.VERIFIED_REFERENCE, 20);
        var result = evaluator.evaluate(session, observation, UTC_NOW.plusSeconds(20));
        assertThat(result.remainingOccurrenceGap()).isEqualTo(1);
        assertThat(result.checkpoint().getIndex()).isEqualTo(2);
        assertHeld(evaluator.evaluate(session, new BoardingProgressObservation(session.provider(), session.routeId(), null,
                observation.vehicle(), Meaning.VERIFIED_REFERENCE, false, null, true), UTC_NOW.plusSeconds(20)),
                BoardingStateReason.TRAVERSAL_AMBIGUOUS);
    }

    @ParameterizedTest
    @EnumSource(value = BoardingStateReason.class, names = {"VEHICLE_MISSING", "IDENTITY_CONFLICT", "PROGRESS_REGRESSION",
            "TRAVERSAL_AMBIGUOUS", "CONTINUITY_GAP_EXCEEDED"})
    @DisplayName("연속성 상실 사유는 정상 차량이 재등장해도 재확인 전까지 보존한다")
    void preserve_continuity_loss(BoardingStateReason reason) {
        var session = snapshot(snapshot(0).traversal(), 0, BoardingSessionStatus.SUSPENDED, reason);
        assertHeld(evaluator.evaluate(session, observation(session, 1, Meaning.VERIFIED_REFERENCE, 20), UTC_NOW.plusSeconds(20)), reason);
    }

    @ParameterizedTest
    @EnumSource(value = BoardingStateReason.class, names = {"TEMPORARY_PROVIDER_FAILURE", "STALE_PROGRESS", "METADATA_CHANGED"})
    @DisplayName("자동 재검증 가능한 보류는 같은 snapshot과 새 유효 관측에서 판단을 재개할 수 있다")
    void resume_temporary_hold(BoardingStateReason reason) {
        var session = snapshot(snapshot(0).traversal(), 0, BoardingSessionStatus.SUSPENDED, reason);
        assertThat(evaluator.evaluate(session, observation(session, 1, Meaning.VERIFIED_REFERENCE, 20), UTC_NOW.plusSeconds(20)).outcome())
                .isEqualTo(Outcome.PREPARE_ALIGHT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S", "PT0.000000001S", "PT999999999999S"})
    @DisplayName("준비 TTL은 양수이며 MySQL UTC microsecond 범위에 들어야 한다")
    void validate_configuration(String value) {
        assertThatThrownBy(() -> new BoardingProgressEvaluator(Duration.parse(value)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BoardingProgressObservation replace(BoardingProgressObservation base, Vehicle vehicle) {
        return new BoardingProgressObservation(base.provider(), base.routeId(), base.cityCode(), vehicle,
                base.meaning(), base.orderOnlyVerified(), base.directionContext(), base.traversalAmbiguous());
    }

    private static void assertHeld(BoardingTrackingDecision result, BoardingStateReason reason) {
        assertThat(result.outcome()).isEqualTo(Outcome.HELD);
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.checkpoint()).isNull();
        assertThat(result.remainingOccurrenceGap()).isNull();
    }
}
