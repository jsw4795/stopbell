package com.stopbell.boarding.service;

import static com.stopbell.boarding.BoardingSessionFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import com.stopbell.boarding.domain.*;
import com.stopbell.boarding.domain.BoardingProgressObservation.Meaning;
import com.stopbell.boarding.domain.BoardingTrackingDecision.Outcome;
import com.stopbell.boarding.dto.BoardingSessionResponse.EvaluationStatus;
import com.stopbell.boarding.entity.*;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.service.*;
import com.stopbell.transit.client.*;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.seoul.*;
import com.stopbell.transit.entity.*;
import com.stopbell.transit.repository.*;
import com.stopbell.transit.service.VehiclePollingCycle;
import com.stopbell.transit.mapper.SeoulBusTransitObservationMapper;
import com.stopbell.transit.mapper.TagoTransitObservationMapper;
import com.stopbell.user.entity.*;
import com.stopbell.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@Testcontainers
class BoardingTrackingIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");
    @Autowired private BoardingTrackingStore store;
    @Autowired private BoardingSessionPollingService polling;
    @Autowired private BoardingTrackingRuntime runtime;
    @Autowired private BoardingProgressEvaluator evaluator;
    @Autowired private BoardingSessionService api;
    @Autowired private BoardingSessionRepository sessions;
    @Autowired private UserRepository users;
    @Autowired private BusRouteRepository routes;
    @Autowired private BusStopRepository stops;
    @Autowired private BusRouteStopOccurrenceRepository occurrences;
    @Autowired private BoardingTrackingMetadata metadata;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private BoardingVehicleTracker tracker;
    @MockitoBean(name = "transitMetadataClock") private Clock clock;
    @MockitoBean private SeoulBusVehicleLocationClient seoul;
    @MockitoBean private SeoulBusVehicleDetailClient detail;
    @MockitoBean private TagoVehicleLocationClient tago;
    private final AtomicReference<Instant> time = new AtomicReference<>();
    private BusRoute route;
    private List<BoardingTraversalSnapshot.Occurrence> traversal;
    private BoardingSession session;

    @BeforeEach
    void set_up() {
        runtime.admittedIds().forEach(runtime::forget);
        sessions.deleteAll();
        occurrences.deleteAll();
        routes.deleteAll();
        stops.deleteAll();
        users.deleteAll();
        route = routes.saveAndFlush(new BusRoute(TransitProvider.SEOUL_BUS, "route-id", "7000", null));
        traversal = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            var stop = stops.saveAndFlush(new BusStop(TransitProvider.SEOUL_BUS, "s" + index, "정류장" + index, null, null));
            int order = BoardingProgressEvaluatorTest.ORDERS.get(index);
            var occurrence = occurrences.saveAndFlush(new BusRouteStopOccurrence(route, stop, order));
            traversal.add(new BoardingTraversalSnapshot.Occurrence(occurrence.getId(), stop.getExternalStopId(), order));
        }
        session = create();
        time.set(NOW.plusSeconds(20));
        when(clock.instant()).thenAnswer(invocation -> time.get());
        when(seoul.fetchVehicleLocations(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return roster();
        });
        when(detail.fetchVehicleDetail(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return detail(1);
        });
    }

    private BoardingSession create() {
        var user = users.saveAndFlush(new User(AuthProvider.GOOGLE, UUID.randomUUID().toString()));
        var snapshot = new BoardingTraversalSnapshot(1, TransitProvider.SEOUL_BUS, "route-id", null, traversal,
                3, BoardingTraversalSnapshot.AlightAvailability.ALLOWED, "controlled-test-evidence");
        var checkpoint = new BoardingCheckpoint("s0", 10, 0, BoardingCheckpoint.Evidence.ID_ORDER, null, UTC_NOW, UTC_NOW.minusSeconds(1));
        var saved = sessions.saveAndFlush(new BoardingSession(user, UUID.randomUUID().toString(), "a".repeat(64),
                "vehicle-id", NUMBER, "7000", "목적지", snapshot, checkpoint, UTC_NOW, UTC_NOW.plusHours(4)));
        runtime.admitCreated(saved.getId());
        return saved;
    }

    @Test
    @DisplayName("기존 Alarm과 탑승 세션이 같은 Scheduler cycle의 roster와 detail을 공유하며 중복 실행도 checkpoint를 덮어쓰지 않는다")
    void alarm_and_boarding_share_scheduler_cycle() throws Exception {
        var alarmPolling = mock(BusAlarmPollingService.class);
        var alarmEvaluator = mock(BusAlarmEvaluator.class);
        var lifecycle = mock(BusAlarmLifecycleService.class);
        var alarm = mock(Alarm.class);
        var target = mock(BusAlarmTarget.class);
        when(alarm.getId()).thenReturn(99L);
        when(alarm.getActivationGeneration()).thenReturn(1L);
        when(alarm.getStatus()).thenReturn(AlarmStatus.ACTIVE);
        when(alarm.getBusAlarmTarget()).thenReturn(target);
        when(target.getProvider()).thenReturn(TransitProvider.SEOUL_BUS);
        when(target.getExternalRouteId()).thenReturn("route-id");
        when(alarmPolling.findMonitoringGroups()).thenReturn(List.of(new BusAlarmPollingGroup(
                new BusPollingKey(TransitProvider.SEOUL_BUS, "route-id", null), List.of(alarm))));
        when(alarmEvaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(
                new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false));
        when(lifecycle.applyIfCurrent(any(), any(), any(), any())).thenReturn(new BusAlarmDecisionResult(
                BusAlarmDecisionResult.Outcome.COMMITTED, 1L, AlarmStatus.ACTIVE, null, null));
        var scheduler = new BusAlarmMonitoringScheduler(alarmPolling, alarmEvaluator, lifecycle, tago, seoul, detail,
                new TagoTransitObservationMapper(clock), new SeoulBusTransitObservationMapper(clock), clock, Duration.ZERO, polling);
        scheduler.pollMonitoringAlarms();
        verify(seoul, times(1)).fetchVehicleLocations(any());
        verify(detail, times(1)).fetchVehicleDetail("vehicle-id");
        assertThat(sessions.findById(session.getId()).orElseThrow().getRevision()).isEqualTo(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(scheduler::pollMonitoringAlarms);
            var b = pool.submit(scheduler::pollMonitoringAlarms);
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        }
        assertThat(sessions.findById(session.getId()).orElseThrow().getRevision()).isEqualTo(1);
        verify(seoul, times(3)).fetchVehicleLocations(any());
        verify(detail, times(3)).fetchVehicleDetail("vehicle-id");
        verify(lifecycle, times(3)).applyIfCurrent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("표시 이름만 바뀐 metadata는 구조 변경으로 보류하지 않는다")
    void display_metadata_does_not_change_structure() {
        var stop = stops.findAll().getFirst();
        stop.updateMetadata("새 표시 이름", null, null);
        stops.saveAndFlush(stop);
        assertThat(polling.poll(cycle()).getFirst().decision().outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROUTE-ID", "route-id "})
    @DisplayName("metadata 조회의 DB collation과 관계없이 opaque Route 식별자의 대소문자와 공백 변경은 구조 변경으로 보류한다")
    void opaque_metadata_context(String changed) {
        jdbc.update("update bus_routes set external_route_id=? where id=?", changed, route.getId());
        var result = polling.poll(cycle()).getFirst();
        assertThat(result.decision().outcome()).isEqualTo(Outcome.HELD);
        assertThat(result.decision().reason()).isEqualTo(BoardingStateReason.METADATA_CHANGED);
        verifyNoInteractions(seoul, detail, tago);
    }

    @Test
    @DisplayName("같은 Route와 차량의 두 세션은 roster·detail 한 번을 공유하고 commit한 checkpoint와 revision만 READY로 공개한다")
    void shared_poll_and_live_response() {
        var second = create();
        var results = polling.poll(cycle());
        assertThat(results).hasSize(2).allSatisfy(result -> {
            assertThat(result.accepted()).isTrue();
            assertThat(result.revision()).isEqualTo(1);
            assertThat(result.decision().outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
            assertThat(result.decision().remainingOccurrenceGap()).isEqualTo(2);
        });
        verify(seoul, times(1)).fetchVehicleLocations(any());
        verify(detail, times(1)).fetchVehicleDetail("vehicle-id");
        verifyNoInteractions(tago);
        assertThat(sessions.findById(second.getId()).orElseThrow().getCheckpoint().getIndex()).isEqualTo(1);
        var response = api.find(session.getUser().getId(), session.getId());
        assertThat(response.evaluationStatus()).isEqualTo(EvaluationStatus.READY);
        assertThat(response.remainingOccurrenceGap()).isEqualTo(2);
        var loaded = sessions.findById(session.getId()).orElseThrow();
        assertThat(new BoardingTrackingRuntime().response(loaded, utc()).evaluationStatus()).isEqualTo(EvaluationStatus.RECOVERY_PENDING);
        time.set(NOW.plusSeconds(81));
        assertThat(api.find(session.getUser().getId(), session.getId()).remainingOccurrenceGap()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isZero();
    }

    @Test
    @DisplayName("반복 제공 데이터는 checkpoint 관측시각과 revision을 늘리지 않고 새 후보로 재해석하지 않는다")
    void repeat_keeps_original_checkpoint() {
        polling.poll(cycle());
        var first = sessions.findById(session.getId()).orElseThrow();
        var result = polling.poll(cycle()).getFirst();
        assertThat(result.decision().outcome()).isEqualTo(Outcome.NO_CANDIDATE);
        var repeated = sessions.findById(session.getId()).orElseThrow();
        assertThat(repeated.getRevision()).isEqualTo(first.getRevision());
        assertThat(repeated.getCheckpoint()).isEqualTo(first.getCheckpoint());
    }

    @Test
    @DisplayName("재시작 직후에는 복구 대기이며 새 관측의 복구 commit 뒤에만 READY와 준비 후보를 공개한다")
    void recovery_remains_pending() {
        runtime.forget(session.getId());
        var pending = api.find(session.getUser().getId(), session.getId());
        assertThat(pending.evaluationStatus()).isEqualTo(EvaluationStatus.RECOVERY_PENDING);
        assertThat(pending.remainingOccurrenceGap()).isNull();
        verifyNoInteractions(seoul, detail, tago);
        var recovered = polling.poll(cycle()).getFirst();
        assertThat(recovered.accepted()).isTrue();
        assertThat(recovered.decision().outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
        assertThat(api.find(session.getUser().getId(), session.getId()).evaluationStatus()).isEqualTo(EvaluationStatus.READY);
        assertThat(recovered.decision().observedAt()).isEqualTo(utc());
    }

    @Test
    @DisplayName("등록 세션이 없으면 외부 Provider 호출 없이 반환한다")
    void no_session_no_io() {
        sessions.deleteAll();
        assertThat(polling.poll(cycle())).isEmpty();
        verifyNoInteractions(seoul, detail, tago);
    }

    @Test
    @DisplayName("Provider 실패는 소실로 바꾸지 않으며 다음 cycle의 새 관측으로 자동 재검증한다")
    void temporary_failure_can_resume() {
        when(seoul.fetchVehicleLocations(any())).thenThrow(TransitProviderClientException.protocol(TransitProvider.SEOUL_BUS, "roster", null));
        polling.poll(cycle());
        var held = sessions.findById(session.getId()).orElseThrow();
        assertThat(held.getStateReason()).isEqualTo(BoardingStateReason.TEMPORARY_PROVIDER_FAILURE);
        assertThat(held.getCheckpoint().getIndex()).isZero();
        assertThat(api.find(held.getUser().getId(), held.getId()).evaluationStatus()).isEqualTo(EvaluationStatus.HELD);
        doAnswer(invocation -> roster()).when(seoul).fetchVehicleLocations(any());
        time.set(NOW.plusSeconds(40));
        var resumed = polling.poll(cycle()).getFirst();
        assertThat(resumed.status()).isEqualTo(BoardingSessionStatus.TRACKING);
        assertThat(resumed.decision().outcome()).isEqualTo(Outcome.PREPARE_ALIGHT);
        assertThat(resumed.revision()).isEqualTo(2);
    }

    @Test
    @DisplayName("정상 empty roster의 소실 사유는 재등장과 일시 실패로 덮어쓰거나 자동 해제하지 않는다")
    void missing_is_persisted() {
        when(seoul.fetchVehicleLocations(any())).thenReturn(new SeoulBusVehicleLocationResponse(
                new SeoulBusVehicleLocationResponse.Header("4", null), null));
        polling.poll(cycle());
        var held = sessions.findById(session.getId()).orElseThrow();
        assertThat(held.getStateReason()).isEqualTo(BoardingStateReason.VEHICLE_MISSING);
        assertThat(held.getCheckpoint().getIndex()).isZero();
        assertThat(held.getRevision()).isEqualTo(1);
        doAnswer(invocation -> roster()).when(seoul).fetchVehicleLocations(any());
        assertThat(polling.poll(cycle())).isEmpty();
        var current = sessions.findById(session.getId()).orElseThrow();
        assertThat(current.getStateReason()).isEqualTo(BoardingStateReason.VEHICLE_MISSING);
        assertThat(current.getRevision()).isEqualTo(1);
        assertThat(new BoardingTrackingRuntime().response(current, utc()).evaluationStatus()).isEqualTo(EvaluationStatus.HELD);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("polling 중 취소 또는 만료된 세션에는 늦은 정상 결과와 후보를 적용하지 않는다")
    void terminal_during_io(boolean expired) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(tracker).observe(any(), any());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> polling.poll(cycle()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            if (expired) {
                time.set(NOW.plusSeconds(14400));
            } else {
                api.cancel(session.getUser().getId(), session.getId(), 0);
            }
            release.countDown();
            var result = pending.get(10, TimeUnit.SECONDS).getFirst();
            assertThat(result.accepted()).isFalse();
            assertThat(result.decision()).isNull();
        } finally {
            release.countDown();
        }
        var ended = sessions.findById(session.getId()).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(expired ? BoardingSessionStatus.EXPIRED : BoardingSessionStatus.CANCELLED);
        assertThat(ended.getCheckpoint().getIndex()).isZero();
        assertThat(ended.getRevision()).isEqualTo(1);
    }

    @Test
    @DisplayName("새 checkpoint 또는 SUSPENDED가 반영되면 이전 revision의 늦은 평가 결과를 거부한다")
    void stale_result_cannot_overwrite() {
        var old = store.findOpen().getFirst();
        var oldDecision = decision(old, 1);
        var applied = store.apply(old, decision(old, 2));
        assertThat(applied.revision()).isEqualTo(1);
        assertThat(store.apply(old, oldDecision).accepted()).isFalse();
        var latest = store.findOpen().getFirst();
        assertThat(latest.checkpoint().getIndex()).isEqualTo(2);
        store.apply(latest, BoardingTrackingDecision.held(BoardingStateReason.IDENTITY_CONFLICT));
        assertThat(store.apply(latest, decision(latest, 2)).accepted()).isFalse();
        assertThat(store.findOpen().getFirst().reason()).isEqualTo(BoardingStateReason.IDENTITY_CONFLICT);
    }

    @Test
    @DisplayName("동일 snapshot의 중복 평가 반영은 MySQL row lock과 revision 비교로 한 번만 성공한다")
    void concurrent_apply() throws Exception {
        var expected = store.findOpen().getFirst();
        var decision = decision(expected, 1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> store.apply(expected, decision));
            var b = pool.submit(() -> store.apply(expected, decision));
            assertThat(List.of(a.get(10, TimeUnit.SECONDS).accepted(), b.get(10, TimeUnit.SECONDS).accepted()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(sessions.findById(session.getId()).orElseThrow().getRevision()).isEqualTo(1);
    }

    @Test
    @DisplayName("Provider 조회 후 metadata 구조가 바뀌면 원래 snapshot을 보존하며 후보 대신 METADATA_CHANGED를 저장한다")
    void metadata_race() {
        var expected = store.findOpen().getFirst();
        var decision = decision(expected, 1);
        var addedStop = stops.saveAndFlush(new BusStop(TransitProvider.SEOUL_BUS, "new", "새 정류장", null, null));
        occurrences.saveAndFlush(new BusRouteStopOccurrence(route, addedStop, 200));
        var applied = store.apply(expected, decision);
        assertThat(applied.decision().outcome()).isEqualTo(Outcome.HELD);
        var held = sessions.findById(session.getId()).orElseThrow();
        assertThat(held.getStateReason()).isEqualTo(BoardingStateReason.METADATA_CHANGED);
        assertThat(held.getTraversal()).isEqualTo(expected.traversal());
        assertThat(held.getCheckpoint()).isEqualTo(expected.checkpoint());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    @DisplayName("충분한 직접 도착과 같은 운행의 목적지 이후 근거는 서로 다른 종료 사유를 저장한다")
    void completion_reasons(int index) {
        var expected = store.findOpen().getFirst();
        var observation = BoardingProgressEvaluatorTest.observation(expected, index, Meaning.DIRECT_ARRIVAL, 20);
        var decision = evaluator.evaluate(expected, observation, utc());
        var result = store.apply(expected, decision);
        assertThat(result.status()).isEqualTo(BoardingSessionStatus.COMPLETED);
        var ended = sessions.findById(session.getId()).orElseThrow();
        assertThat(ended.getStateReason()).isEqualTo(index == 3 ? BoardingStateReason.ARRIVAL_OBSERVED : BoardingStateReason.PASSED_OBSERVED);
        assertThat(ended.getEndedAt()).isEqualTo(utc());
        assertThat(ended.getCheckpoint().getIndex()).isEqualTo(index);
        assertThat(api.find(ended.getUser().getId(), ended.getId()).evaluationStatus()).isEqualTo(EvaluationStatus.ENDED);
        assertThat(store.apply(expected, decision).accepted()).isFalse();
    }

    @Test
    @DisplayName("복구 대기 세션도 수명이 끝나면 Provider 조회 없이 EXPIRED로 정리한다")
    void expiration_without_recovery() {
        runtime.forget(session.getId());
        time.set(NOW.plusSeconds(14400));
        assertThat(polling.poll(cycle())).isEmpty();
        assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(BoardingSessionStatus.EXPIRED);
        verifyNoInteractions(seoul, detail, tago);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("commit 실패 또는 응답 유실은 후보·READY를 공개하지 않으며 오래된 결과를 자동 재시도하지 않는다")
    void commit_failure(boolean committed) {
        var expected = store.findOpen().getFirst();
        PlatformTransactionManager failing = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                if (committed) { manager.commit(status); } else { manager.rollback(status); }
                throw new TransactionSystemException("controlled commit failure");
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        var tested = new BoardingTrackingStore(sessions, metadata, runtime, failing, clock);
        assertThatThrownBy(() -> tested.apply(expected, decision(expected, 1))).isInstanceOf(TransactionSystemException.class);
        var current = sessions.findById(session.getId()).orElseThrow();
        assertThat(current.getRevision()).isEqualTo(committed ? 1 : 0);
        assertThat(runtime.response(current, utc()).evaluationStatus()).isEqualTo(EvaluationStatus.RECOVERY_PENDING);
        verifyNoInteractions(seoul, detail, tago);
        if (committed) {
            assertThat(store.apply(expected, decision(expected, 1)).accepted()).isFalse();
        } else {
            // A fresh snapshot and fresh Provider observation in the next normal cycle, never blind replay.
            assertThat(polling.poll(cycle()).getFirst().accepted()).isTrue();
        }
    }

    @Test
    @DisplayName("저장 lock을 기다리는 동안 준비 TTL이 지나면 checkpoint만 저장하고 유효 후보를 공개하지 않는다")
    void ttl_expires_before_apply() {
        var expected = store.findOpen().getFirst();
        var decision = decision(expected, 1);
        time.set(NOW.plusSeconds(50));
        var applied = store.apply(expected, decision);
        assertThat(applied.decision().outcome()).isEqualTo(Outcome.EXPIRED_CANDIDATE);
        assertThat(applied.revision()).isEqualTo(1);
        assertThat(sessions.findById(session.getId()).orElseThrow().getCheckpoint().getObservedAt()).isEqualTo(UTC_NOW.plusSeconds(20));
    }

    @Test
    @DisplayName("commit이 지연되어 준비 TTL이 지나면 commit 확인 후에도 만료된 후보만 반환한다")
    void ttl_expires_during_commit() {
        var expected = store.findOpen().getFirst();
        PlatformTransactionManager delayed = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                time.set(NOW.plusSeconds(50));
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        var tested = new BoardingTrackingStore(sessions, metadata, runtime, delayed, clock);
        var result = tested.apply(expected, decision(expected, 1));
        assertThat(result.accepted()).isTrue();
        assertThat(result.decision().outcome()).isEqualTo(Outcome.EXPIRED_CANDIDATE);
        assertThat(result.decision().observedAt()).isEqualTo(UTC_NOW.plusSeconds(20));
        assertThat(result.decision().expiresAt()).isEqualTo(UTC_NOW.plusSeconds(50));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    @DisplayName("재시작 뒤 같은 occurrence·전진·관측 누락을 새 근거로 복구하며 목적지 이후 준비 알림은 소급하지 않는다")
    void restart_progress(int index) {
        runtime.forget(session.getId());
        when(detail.fetchVehicleDetail(anyString())).thenAnswer(invocation -> detail(index));
        var result = polling.poll(cycle()).getFirst();
        assertThat(result.accepted()).isTrue();
        assertThat(result.decision().checkpoint().getIndex()).isEqualTo(index);
        assertThat(result.decision().outcome()).isEqualTo(switch (index) {
            case 1, 2 -> Outcome.PREPARE_ALIGHT;
            case 3 -> Outcome.ARRIVAL_OBSERVED;
            case 4 -> Outcome.PASSED_OBSERVED;
            default -> Outcome.NO_CANDIDATE;
        });
        var response = api.find(session.getUser().getId(), session.getId());
        assertThat(response.evaluationStatus()).isEqualTo(index < 3 ? EvaluationStatus.READY : EvaluationStatus.ENDED);
    }

    @ParameterizedTest
    @ValueSource(ints = {120, 121})
    @DisplayName("재시작 복구 공백은 마지막 검증 관측부터 계산하며 2분 경계를 초과하면 재확인을 요구한다")
    void recovery_gap_boundary(int seconds) {
        runtime.forget(session.getId());
        time.set(NOW.plusSeconds(seconds));
        var result = polling.poll(cycle()).getFirst();
        assertThat(result.status()).isEqualTo(seconds == 120 ? BoardingSessionStatus.TRACKING : BoardingSessionStatus.SUSPENDED);
        if (seconds > 120) {
            assertThat(result.decision().reason()).isEqualTo(BoardingStateReason.CONTINUITY_GAP_EXCEEDED);
            assertThat(result.decision().checkpoint()).isNull();
        }
    }

    @Test
    @DisplayName("재시작 후 동일 제공시각의 과거 checkpoint 반복은 gate를 열거나 준비 후보를 만들지 않는다")
    void restart_repeated_checkpoint() {
        polling.poll(cycle());
        runtime.forget(session.getId());
        var result = polling.poll(cycle()).getFirst();
        assertThat(result.decision().checkpoint()).isNull();
        assertThat(result.decision().outcome()).isEqualTo(Outcome.NO_CANDIDATE);
        assertThat(api.find(session.getUser().getId(), session.getId()).evaluationStatus()).isEqualTo(EvaluationStatus.RECOVERY_PENDING);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = BoardingStateReason.class, names = {
            "VEHICLE_MISSING", "IDENTITY_CONFLICT", "PROGRESS_REGRESSION", "TRAVERSAL_AMBIGUOUS", "CONTINUITY_GAP_EXCEEDED"})
    @DisplayName("알려진 연속성 상실은 재시작 후에도 사유와 checkpoint를 보존하며 Provider 자동 연결을 시도하지 않는다")
    void restart_preserves_hold(BoardingStateReason reason) {
        store.apply(store.findOpen().getFirst(), BoardingTrackingDecision.held(reason));
        runtime.forget(session.getId());
        assertThat(polling.poll(cycle())).isEmpty();
        assertThat(store.findOpen().getFirst().reason()).isEqualTo(reason);
        verifyNoInteractions(seoul, detail, tago);
    }

    @Test
    @DisplayName("재시작 후 Provider 실패를 자동 재조회하다 공백을 초과하면 소실 대신 연속성 공백 사유를 저장한다")
    void restart_provider_failure_gap() {
        runtime.forget(session.getId());
        when(seoul.fetchVehicleLocations(any())).thenThrow(TransitProviderClientException.protocol(TransitProvider.SEOUL_BUS, "roster", null));
        polling.poll(cycle());
        assertThat(store.findOpen().getFirst().reason()).isEqualTo(BoardingStateReason.TEMPORARY_PROVIDER_FAILURE);
        time.set(NOW.plusSeconds(121));
        polling.poll(cycle());
        assertThat(store.findOpen().getFirst().reason()).isEqualTo(BoardingStateReason.CONTINUITY_GAP_EXCEEDED);
        time.set(NOW.plusSeconds(140));
        doAnswer(invocation -> roster()).when(seoul).fetchVehicleLocations(any());
        assertThat(polling.poll(cycle())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("복구 commit 응답 유실은 같은 결과를 새 transaction에서 재조회하고 rollback이면 gate를 닫아 둔다")
    void recovery_commit_reconciliation(boolean persisted) {
        runtime.forget(session.getId());
        var expected = store.findOpen().getFirst();
        var once = new java.util.concurrent.atomic.AtomicBoolean(true);
        PlatformTransactionManager uncertain = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                if (once.getAndSet(false)) {
                    assertThat(runtime.response(session, utc()).evaluationStatus()).isEqualTo(EvaluationStatus.RECOVERY_PENDING);
                    if (persisted) { manager.commit(status); } else { manager.rollback(status); }
                    throw new TransactionSystemException("controlled acknowledgement loss");
                }
                manager.commit(status);
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        var tested = new BoardingTrackingStore(sessions, metadata, runtime, uncertain, clock);
        var result = tested.apply(expected, decision(expected, 1));
        assertThat(result.accepted()).isEqualTo(persisted);
        assertThat(api.find(session.getUser().getId(), session.getId()).evaluationStatus())
                .isEqualTo(persisted ? EvaluationStatus.READY : EvaluationStatus.RECOVERY_PENDING);
        assertThat(sessions.findById(session.getId()).orElseThrow().getRevision()).isEqualTo(persisted ? 1 : 0);
        verifyNoInteractions(seoul, detail, tago);
    }

    @Test
    @DisplayName("복구 commit 후 runtime 등록 직전 취소가 commit되면 늦은 복구 callback이 gate를 다시 열지 못한다")
    void cancel_before_recovery_publication() {
        runtime.forget(session.getId());
        var expected = store.findOpen().getFirst();
        PlatformTransactionManager delayed = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                api.cancel(session.getUser().getId(), session.getId(), 1);
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        var result = new BoardingTrackingStore(sessions, metadata, runtime, delayed, clock).apply(expected, decision(expected, 1));
        assertThat(result.accepted()).isFalse();
        assertThat(result.decision()).isNull();
        var current = sessions.findById(session.getId()).orElseThrow();
        assertThat(current.getStatus()).isEqualTo(BoardingSessionStatus.CANCELLED);
        assertThat(runtime.isReady(BoardingTrackingSnapshot.from(current), utc())).isFalse();
        var late = new BoardingTrackingSnapshot(expected.id(), 1, BoardingSessionStatus.TRACKING, null,
                expected.provider(), expected.routeId(), expected.cityCode(), expected.vehicleId(), expected.vehicleNumber(),
                expected.traversal(), decision(expected, 1).checkpoint(), expected.expiresAt());
        runtime.verified(late);
        assertThat(runtime.isReady(late, utc())).isFalse();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,IDENTITY_CONFLICT", "1,IDENTITY_CONFLICT", "2,PROGRESS_REGRESSION",
            "3,TRAVERSAL_AMBIGUOUS", "4,STALE_PROGRESS", "5,STALE_PROGRESS", "6,TRAVERSAL_AMBIGUOUS"})
    @DisplayName("복구의 ID·번호 충돌과 제공시각 역행·상충·stale·future·다른 lap은 checkpoint와 허용을 갱신하지 않는다")
    void restart_rejects_invalid_evidence(int scenario, BoardingStateReason reason) {
        runtime.forget(session.getId());
        var expected = store.findOpen().getFirst();
        var observation = BoardingProgressEvaluatorTest.observation(expected, 1, Meaning.VERIFIED_REFERENCE, 20);
        var vehicle = observation.vehicle();
        vehicle = switch (scenario) {
            case 0 -> new com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle("other", vehicle.number(), vehicle.stopId(),
                    vehicle.order(), 0, vehicle.observedAt(), vehicle.providerDataTime(), null, null);
            case 1 -> new com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle(vehicle.id(), "다른번호1234", vehicle.stopId(),
                    vehicle.order(), 0, vehicle.observedAt(), vehicle.providerDataTime(), null, null);
            case 2 -> new com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle(vehicle.id(), vehicle.number(), vehicle.stopId(),
                    vehicle.order(), 0, vehicle.observedAt(), NOW.minusSeconds(2), null, null);
            case 3 -> new com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle(vehicle.id(), vehicle.number(), vehicle.stopId(),
                    vehicle.order(), 0, vehicle.observedAt(), NOW.minusSeconds(1), null, null);
            case 4 -> new com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle(vehicle.id(), vehicle.number(), vehicle.stopId(),
                    vehicle.order(), 0, NOW.minusSeconds(61), NOW.minusSeconds(62), null, null);
            case 5 -> new com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle(vehicle.id(), vehicle.number(), vehicle.stopId(),
                    vehicle.order(), 0, NOW.plusSeconds(21), NOW.plusSeconds(20), null, null);
            default -> vehicle;
        };
        observation = new BoardingProgressObservation(expected.provider(), expected.routeId(), expected.cityCode(), vehicle,
                observation.meaning(), false, null, scenario == 6);
        doReturn(new BoardingVehicleTracker.Input(observation, null)).when(tracker).observe(any(), any());
        var result = polling.poll(cycle()).getFirst();
        assertThat(result.decision().reason()).isEqualTo(reason);
        var held = sessions.findById(session.getId()).orElseThrow();
        assertThat(held.getCheckpoint()).isEqualTo(expected.checkpoint());
        assertThat(runtime.isReady(BoardingTrackingSnapshot.from(held), utc())).isFalse();
        assertThat(api.find(held.getUser().getId(), held.getId()).remainingOccurrenceGap()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("미복구 세션의 검증 중 취소·만료가 되어도 최신 terminal과 revision을 보존한다")
    void recovery_terminal_during_io(boolean expired) throws Exception {
        runtime.forget(session.getId());
        terminal_during_io(expired);
    }

    @Test
    @DisplayName("미복구 snapshot의 중복 반영과 새로운 checkpoint 경합은 row lock과 revision으로 직렬화한다")
    void recovery_concurrent_results() throws Exception {
        runtime.forget(session.getId());
        concurrent_apply();
        var latest = store.findOpen().getFirst();
        assertThat(latest.revision()).isEqualTo(1);
        var decision = evaluator.evaluate(latest,
                BoardingProgressEvaluatorTest.observation(latest, 2, Meaning.VERIFIED_REFERENCE, 40), UTC_NOW.plusSeconds(40));
        time.set(NOW.plusSeconds(40));
        assertThat(store.apply(latest, decision).accepted()).isTrue();
        assertThat(store.apply(latest, decision).accepted()).isFalse();
        assertThat(store.findOpen().getFirst().checkpoint().getIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("재시작 복구도 metadata 재확인에서 구조 경합을 거부하고 snapshot을 교체하지 않는다")
    void recovery_metadata_race() {
        runtime.forget(session.getId());
        metadata_race();
        var current = store.findOpen().getFirst();
        assertThat(current.reason()).isEqualTo(BoardingStateReason.METADATA_CHANGED);
        assertThat(runtime.isReady(current, utc())).isFalse();
    }

    @Test
    @DisplayName("current 조회가 만료 세션을 빈 목록으로 반환하더라도 이전 허용 revision은 commit 뒤 닫힌다")
    void current_query_expiration_closes_gate() {
        polling.poll(cycle());
        var validated = store.findOpen().getFirst();
        assertThat(runtime.isReady(validated, utc())).isTrue();
        time.set(NOW.plusSeconds(14400));
        assertThat(api.findCurrent(session.getUser().getId())).isEmpty();
        assertThat(runtime.isReady(validated, UTC_NOW.plusSeconds(20))).isFalse();
        assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(BoardingSessionStatus.EXPIRED);
    }

    @Test
    @DisplayName("복구 성공 commit 직후 식별 충돌이 commit되면 오래된 후보 공개와 gate 재허용을 차단한다")
    void identity_conflict_before_recovery_publication() {
        runtime.forget(session.getId());
        var expected = store.findOpen().getFirst();
        PlatformTransactionManager delayed = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                store.apply(store.findOpen().getFirst(), BoardingTrackingDecision.held(BoardingStateReason.IDENTITY_CONFLICT));
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        var result = new BoardingTrackingStore(sessions, metadata, runtime, delayed, clock).apply(expected, decision(expected, 1));
        assertThat(result.accepted()).isFalse();
        assertThat(result.decision()).isNull();
        assertThat(store.findOpen().getFirst().reason()).isEqualTo(BoardingStateReason.IDENTITY_CONFLICT);
        assertThat(api.find(session.getUser().getId(), session.getId()).evaluationStatus()).isEqualTo(EvaluationStatus.HELD);
    }

    private BoardingTrackingDecision decision(BoardingTrackingSnapshot expected, int index) {
        return evaluator.evaluate(expected, BoardingProgressEvaluatorTest.observation(expected, index, Meaning.VERIFIED_REFERENCE, 20), utc());
    }

    private VehiclePollingCycle cycle() { return new VehiclePollingCycle(tago, seoul, detail, clock); }
    private LocalDateTime utc() { return LocalDateTime.ofInstant(time.get(), ZoneOffset.UTC); }
    private String dataTime() {
        return DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneId.of("Asia/Seoul")).format(time.get().minusSeconds(1));
    }
    private SeoulBusVehicleLocationResponse roster() {
        return new SeoulBusVehicleLocationResponse(new SeoulBusVehicleLocationResponse.Header("0", null),
                new SeoulBusVehicleLocationResponse.Body(List.of(new SeoulBusVehicleLocationItem("vehicle-id", NUMBER,
                        1, "section", 1, dataTime(), null, null, null, null, null))));
    }
    private SeoulBusVehicleDetailResponse detail(int index) {
        return new SeoulBusVehicleDetailResponse(new SeoulBusVehicleDetailResponse.Header("0", null),
                new SeoulBusVehicleDetailResponse.Body(List.of(new SeoulBusVehicleDetailItem("vehicle-id", NUMBER,
                        "s" + index, BoardingProgressEvaluatorTest.ORDERS.get(index), 1, dataTime(), null, null))));
    }
}
