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
    private final BoardingProgressEvaluator evaluator = new BoardingProgressEvaluator(Duration.ofMinutes(2), Duration.ofSeconds(30));
    static final List<Integer> ORDERS = List.of(10, 20, 40, 80, 100);

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
        var configured = new BoardingProgressEvaluator(Duration.ofMinutes(2), Duration.ofSeconds(ttlSeconds));
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
        // Provider time is one second older, so age 60 also fails the observation freshness policy.
        var result = evaluator.evaluate(session, observation, UTC_NOW.plusSeconds(20 + age));
        assertThat(result.outcome()).isEqualTo(age == 60 ? Outcome.HELD : Outcome.EXPIRED_CANDIDATE);
        if (age < 60) {
            assertThat(result.expiresAt()).isEqualTo(UTC_NOW.plusSeconds(50));
            assertThat(result.checkpoint()).isNotNull();
        }
    }

    @Test
    @DisplayName("같은 제공시각의 반복 데이터는 checkpoint와 revision 갱신 입력이나 새 후보를 만들지 않는다")
    void repeated_source_data() {
        var session = snapshot(1);
        var observation = observation(session, 1, Meaning.VERIFIED_REFERENCE, 0);
        var vehicle = observation.vehicle();
        var receivedAgain = new Vehicle(vehicle.id(), vehicle.number(), vehicle.stopId(), vehicle.order(), 0,
                NOW.plusSeconds(20), vehicle.providerDataTime(), null, null);
        var result = evaluator.evaluate(session, replace(observation, receivedAgain), UTC_NOW.plusSeconds(20));
        assertThat(result.outcome()).isEqualTo(Outcome.NO_CANDIDATE);
        assertThat(result.checkpoint()).isNull();
        assertThat(result.observedAt()).isEqualTo(UTC_NOW);
    }

    @Test
    @DisplayName("같은 제공시각인데 진행 기준점이 달라진 응답은 충돌로 보류한다")
    void same_time_conflict() {
        var session = snapshot(0);
        var result = evaluator.evaluate(session, observation(session, 1, Meaning.VERIFIED_REFERENCE, 0), UTC_NOW.plusSeconds(20));
        assertHeld(result, BoardingStateReason.TRAVERSAL_AMBIGUOUS);
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
    @CsvSource({"0,TRAVERSAL_AMBIGUOUS", "1,STALE_PROGRESS", "2,STALE_PROGRESS", "3,PROGRESS_REGRESSION",
            "4,PROGRESS_REGRESSION", "5,CONTINUITY_GAP_EXCEEDED", "6,TRAVERSAL_AMBIGUOUS", "7,TRAVERSAL_AMBIGUOUS",
            "8,IDENTITY_CONFLICT", "9,IDENTITY_CONFLICT", "10,IDENTITY_CONFLICT", "11,IDENTITY_CONFLICT",
            "12,STALE_PROGRESS", "13,STALE_PROGRESS", "14,TRAVERSAL_AMBIGUOUS", "15,TRAVERSAL_AMBIGUOUS"})
    @DisplayName("검증되지 않은 의미와 stale·future·역행·공백·회차·차량 또는 Provider 문맥 충돌은 후보 없이 보류한다")
    void reject_uncertain_observation(int scenario, BoardingStateReason reason) {
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
        assertHeld(evaluator.evaluate(session, observation, UTC_NOW.plusSeconds(scenario == 5 ? 121 : 20)), reason);
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
            assertHeld(result, BoardingStateReason.TRAVERSAL_AMBIGUOUS);
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
    @DisplayName("준비 TTL과 공백 설정은 양수이며 MySQL UTC microsecond 범위에 들어야 한다")
    void validate_configuration(String value) {
        assertThatThrownBy(() -> new BoardingProgressEvaluator(Duration.parse(value), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoardingProgressEvaluator(Duration.ofMinutes(2), Duration.parse(value)))
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
