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
import com.stopbell.notification.entity.*;
import com.stopbell.notification.repository.*;
import com.stopbell.transit.service.BoardingRouteSnapshot;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.params.provider.*;
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
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @Autowired private BoardingNotificationService notifications;
    @MockitoSpyBean private NotificationEventRepository events;
    @MockitoSpyBean private NotificationDeliveryRepository deliveries;
    @Autowired private DeviceRepository devices;
    @Autowired private com.stopbell.alarm.repository.AlarmRepository alarms;
    @MockitoBean private BoardingSessionStartValidator validator;
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
        deliveries.deleteAll();
        events.deleteAll();
        alarms.deleteAll();
        devices.deleteAll();
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
        var request = new com.stopbell.boarding.dto.CreateBoardingSessionRequest(UUID.randomUUID().toString(), "ref", NUMBER);
        var saved = sessions.saveAndFlush(new BoardingSession(user, request.clientRequestId(), request.fingerprint(),
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
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isEqualTo(2);
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
        deliveries.deleteAll();
        events.deleteAll();
        alarms.deleteAll();
        devices.deleteAll();
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
        var tested = new BoardingTrackingStore(sessions, metadata, runtime, failing, clock, notifications);
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
        var tested = new BoardingTrackingStore(sessions, metadata, runtime, delayed, clock, notifications);
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
        var tested = new BoardingTrackingStore(sessions, metadata, runtime, uncertain, clock, notifications);
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
        var result = new BoardingTrackingStore(sessions, metadata, runtime, delayed, clock, notifications).apply(expected, decision(expected, 1));
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
        var result = new BoardingTrackingStore(sessions, metadata, runtime, delayed, clock, notifications).apply(expected, decision(expected, 1));
        assertThat(result.accepted()).isFalse();
        assertThat(result.decision()).isNull();
        assertThat(store.findOpen().getFirst().reason()).isEqualTo(BoardingStateReason.IDENTITY_CONFLICT);
        assertThat(api.find(session.getUser().getId(), session.getId()).evaluationStatus()).isEqualTo(EvaluationStatus.HELD);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    @DisplayName("새 gap 2 또는 1 관측은 Session checkpoint와 소유자·실제 시각을 보존한 Event·Delivery를 원자 저장한다")
    void persist_prepare_with_recipients(int index) {
        var first = device(session.getUser());
        var second = device(session.getUser());
        var expected = store.findOpen().getFirst();
        var decision = decision(expected, index);
        var result = store.apply(expected, decision);
        assertThat(result.accepted()).isTrue();
        var event = onlyEvent();
        assertThat(event.getBoardingSessionId()).isEqualTo(session.getId());
        assertThat(event.getUser().getId()).isEqualTo(session.getUser().getId());
        assertThat(event.getSourceType()).isEqualTo(NotificationSourceType.BOARDING_SESSION);
        assertThat(event.getBoardingEventType()).isEqualTo(BoardingNotificationEventType.PREPARE_ALIGHT);
        assertThat(event.getAlarmId()).isNull();
        assertThat(event.getActivationGeneration()).isNull();
        assertThat(event.getTrackingCycleId()).isNull();
        assertThat(event.getEventType()).isNull();
        assertThat(event.getObservedAt()).isEqualTo(decision.checkpoint().getObservedAt());
        assertThat(event.getEventDetectedAt()).isEqualTo(decision.eventDetectedAt());
        assertThat(event.getCreatedAt()).isEqualTo(utc());
        assertThat(event.getExpiresAt()).isEqualTo(decision.observedAt().plusSeconds(30));
        assertThat(event.getCurrentStopExternalId()).isEqualTo(decision.checkpoint().getStopExternalId());
        assertThat(event.getCurrentStopOrder()).isEqualTo(decision.checkpoint().getStopOrder());
        assertThat(event.getProviderDataTime()).isEqualTo(decision.checkpoint().getProviderDataTime());
        assertThat(event.getLatitude()).isNull();
        assertThat(event.getLongitude()).isNull();
        assertThat(event.getObservedAt().getNano() % 1000).isZero();
        assertThat(deliveries.findAllByNotificationEventIdOrderByIdAsc(event.getId()))
                .extracting(value -> value.getDevice().getId()).containsExactly(first.getId(), second.getId());
        assertThat(deliveries.findAll()).allSatisfy(value -> {
            assertThat(value.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
            assertThat(value.getAttemptCount()).isZero();
            assertThat(value.getExpiresAt()).isEqualTo(event.getExpiresAt());
        });
        assertThat(sessions.findById(session.getId()).orElseThrow().getCheckpoint()).isEqualTo(decision.checkpoint());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    @DisplayName("Event 시점 recipient 수는 고정되며 새 Device·다음 revision·재시작·반복 후보가 fan-out을 늘리지 않는다")
    void fixed_recipients_and_deduplication(int count) {
        for (int i = 0; i < count; i++) { device(session.getUser()); }
        var expected = store.findOpen().getFirst();
        var candidate = decision(expected, 1);
        store.apply(expected, candidate);
        var original = onlyEvent();
        var originalIds = deliveries.findAll().stream().map(NotificationDelivery::getId).toList();
        device(session.getUser());
        assertThat(store.apply(expected, candidate).accepted()).isFalse();
        runtime.forget(session.getId());
        var latest = store.findOpen().getFirst();
        time.set(NOW.plusSeconds(40));
        var next = evaluator.evaluate(latest, BoardingProgressEvaluatorTest.observation(latest, 2,
                Meaning.VERIFIED_REFERENCE, 40), utc());
        assertThat(store.apply(latest, next).accepted()).isTrue();
        assertThat(onlyEvent()).usingRecursiveComparison().isEqualTo(original);
        assertThat(deliveries.findAll()).extracting(NotificationDelivery::getId).containsExactlyElementsOf(originalIds);
        assertThat(deliveries.count()).isEqualTo(count);
    }

    @Test
    @DisplayName("다른 owner·비활성·target 없는 Device는 제외하고 현재 ownership generation만 저장한다")
    void recipient_eligibility_and_generation() {
        var other = users.saveAndFlush(new User(AuthProvider.GOOGLE, UUID.randomUUID().toString()));
        device(other);
        var disabled = device(session.getUser());
        disabled.disable(1);
        devices.saveAndFlush(disabled);
        var missing = device(session.getUser());
        jdbc.update("update devices set current_push_target_id=null, enabled=false where id=?", missing.getId());
        var transferred = device(other);
        transferred.transferOwnership(session.getUser(), "returned-target", 1, utc());
        devices.saveAndFlush(transferred);
        store.apply(store.findOpen().getFirst(), decision(store.findOpen().getFirst(), 1));
        assertThat(deliveries.findAll()).singleElement().satisfies(value -> {
            assertThat(value.getDevice().getId()).isEqualTo(transferred.getId());
            assertThat(value.getRecipientOwnershipGeneration()).isOne();
        });
        transferred.transferOwnership(other, "moved-target", 2, utc());
        devices.saveAndFlush(transferred);
        assertThat(onlyEvent().getUser().getId()).isEqualTo(session.getUser().getId());
        assertThat(deliveries.findAll().getFirst().getRecipientOwnershipGeneration()).isOne();
    }

    @ParameterizedTest
    @ValueSource(longs = {29999999, 30000000, 30000001})
    @DisplayName("최초 INSERT 직전 TTL의 1 microsecond 이전만 Event를 저장하고 경계부터 기회를 소비하지 않는다")
    void insert_ttl_boundary(long micros) {
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        var candidate = decision(expected, 1);
        time.set(NOW.plusSeconds(20).plusNanos(micros * 1000));
        var result = store.apply(expected, candidate);
        assertThat(result.accepted()).isTrue();
        assertThat(events.count()).isEqualTo(micros < 30000000 ? 1 : 0);
        assertThat(deliveries.count()).isEqualTo(events.count());
        assertThat(store.findOpen().getFirst().checkpoint()).isEqualTo(candidate.checkpoint());
        if (micros >= 30000000) {
            time.set(NOW.plusSeconds(60));
            var latest = store.findOpen().getFirst();
            var fresh = evaluator.evaluate(latest, BoardingProgressEvaluatorTest.observation(latest, 2,
                    Meaning.VERIFIED_REFERENCE, 60), utc());
            store.apply(latest, fresh);
            assertThat(onlyEvent().getObservedAt()).isEqualTo(UTC_NOW.plusSeconds(60));
        }
    }

    @Test
    @DisplayName("Session 수명은 준비 Event와 모든 Delivery의 기한 상한이며 조회 만료 시 함께 EXPIRED로 종료한다")
    void session_lifetime_caps_event() {
        jdbc.update("update boarding_sessions set expires_at=? where id=?", UTC_NOW.plusSeconds(35), session.getId());
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        store.apply(expected, decision(expected, 1));
        assertThat(onlyEvent().getExpiresAt()).isEqualTo(UTC_NOW.plusSeconds(35));
        time.set(NOW.plusSeconds(35));
        assertThat(api.find(session.getUser().getId(), session.getId()).status()).isEqualTo(BoardingSessionStatus.EXPIRED);
        assertThat(deliveries.findAll()).singleElement().satisfies(value -> {
            assertThat(value.getExpiresAt()).isEqualTo(UTC_NOW.plusSeconds(35));
            assertThat(value.getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
            assertThat(value.getLastFailureCode()).isEqualTo("FRESHNESS_EXPIRED");
        });
    }

    @ParameterizedTest
    @EnumSource(value = BoardingStateReason.class, names = {"VEHICLE_MISSING", "IDENTITY_CONFLICT",
            "PROGRESS_REGRESSION", "TRAVERSAL_AMBIGUOUS", "CONTINUITY_GAP_EXCEEDED",
            "TEMPORARY_PROVIDER_FAILURE", "STALE_PROGRESS", "METADATA_CHANGED"})
    @DisplayName("연속성 상실은 PENDING을 종료하고 자동 재검증 가능한 일시 보류는 Event와 PENDING을 유지한다")
    void suspension_delivery_policy(BoardingStateReason reason) {
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        store.apply(expected, decision(expected, 1));
        var event = onlyEvent();
        var latest = store.findOpen().getFirst();
        store.apply(latest, BoardingTrackingDecision.held(reason));
        assertThat(deliveries.findAll()).singleElement().satisfies(value -> {
            assertThat(value.getStatus()).isEqualTo(reason.allowsAutomaticRevalidation()
                    ? NotificationDeliveryStatus.PENDING : NotificationDeliveryStatus.FAILED);
            assertThat(value.getLastFailureCode()).isEqualTo(reason.allowsAutomaticRevalidation() ? null : "DISPATCH_NOT_ALLOWED");
            assertThat(value.getAttemptCount()).isZero();
        });
        assertThat(onlyEvent().getId()).isEqualTo(event.getId());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("취소·직접 도착·목적지 통과는 PENDING을 종료하며 기한이 지났다면 EXPIRED를 우선한다")
    void lifecycle_ends_pending(int scenario) {
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        store.apply(expected, decision(expected, 1));
        time.set(NOW.plusSeconds(scenario == 3 ? 50 : 40));
        var latest = store.findOpen().getFirst();
        if (scenario == 0 || scenario == 3) {
            api.cancel(session.getUser().getId(), session.getId(), latest.revision());
        } else {
            store.apply(latest, evaluator.evaluate(latest, BoardingProgressEvaluatorTest.observation(latest,
                    scenario == 1 ? 3 : 4, scenario == 1 ? Meaning.DIRECT_ARRIVAL : Meaning.VERIFIED_REFERENCE, 40), utc()));
        }
        assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(
                scenario == 0 || scenario == 3 ? BoardingSessionStatus.CANCELLED : BoardingSessionStatus.COMPLETED);
        assertThat(deliveries.findAll()).singleElement().satisfies(value -> {
            assertThat(value.getStatus()).isEqualTo(scenario == 3 ? NotificationDeliveryStatus.EXPIRED : NotificationDeliveryStatus.FAILED);
            assertThat(value.getLastFailureCode()).isEqualTo(scenario == 3 ? "FRESHNESS_EXPIRED" : "DISPATCH_NOT_ALLOWED");
        });
        assertThat(events.count()).isOne();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    @DisplayName("상세·current·요청조회·중복생성·취소·재확인·polling의 수명 만료는 Session과 PENDING을 함께 종료한다")
    void expiration_in_every_entry_point(int path) {
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        store.apply(expected, decision(expected, 1));
        time.set(NOW.plusSeconds(14400));
        switch (path) {
            case 0 -> api.find(session.getUser().getId(), session.getId());
            case 1 -> api.findCurrent(session.getUser().getId());
            case 2 -> api.findRequest(session.getUser().getId(), session.getClientRequestId());
            case 3 -> api.create(session.getUser().getId(),
                    new com.stopbell.boarding.dto.CreateBoardingSessionRequest(session.getClientRequestId(), "ref", NUMBER));
            case 4 -> api.cancel(session.getUser().getId(), session.getId(), 1);
            case 5 -> assertThatThrownBy(() -> api.reconfirm(session.getUser().getId(), session.getId(),
                    new com.stopbell.boarding.dto.ReconfirmBoardingSessionRequest(1, "ref", NUMBER)))
                    .isInstanceOf(com.stopbell.common.error.ApiException.class);
            case 6 -> store.expire(store.findOpen().getFirst());
        }
        assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(BoardingSessionStatus.EXPIRED);
        assertThat(deliveries.findAll()).singleElement().satisfies(value -> {
            assertThat(value.getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
            assertThat(value.getLastFailureCode()).isEqualTo("FRESHNESS_EXPIRED");
        });
        var ended = sessions.findById(session.getId()).orElseThrow();
        api.find(session.getUser().getId(), session.getId());
        assertThat(sessions.findById(session.getId()).orElseThrow().getRevision()).isEqualTo(ended.getRevision());
    }

    @Test
    @DisplayName("취소는 PENDING만 종료하고 terminal 상태와 기존 attempt·Provider 결과를 보존한다")
    void preserve_terminal_and_attempt_details() {
        for (int i = 0; i < 4; i++) { device(session.getUser()); }
        var expected = store.findOpen().getFirst();
        store.apply(expected, decision(expected, 1));
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var values = deliveries.findAll();
            for (var value : values) {
                value.beginAttempt(7, utc(), utc().plusSeconds(10));
            }
            values.get(0).markAccepted(utc(), utc());
            values.get(1).recordFailure(NotificationProviderResult.PERMANENT_REQUEST, "REJECTED");
            values.get(1).markFailed("REJECTED", utc());
            values.get(2).expire(UTC_NOW.plusSeconds(50));
            values.get(3).recordFailure(NotificationProviderResult.RETRYABLE, "UNAVAILABLE");
        });
        var before = deliveries.findAll();
        api.cancel(session.getUser().getId(), session.getId(), 1);
        var after = deliveries.findAll();
        for (int i = 0; i < 3; i++) {
            assertThat(after.get(i)).usingRecursiveComparison().ignoringFields("notificationEvent", "device").isEqualTo(before.get(i));
        }
        var pending = after.get(3);
        assertThat(pending.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(pending.getAttemptCount()).isOne();
        assertThat(pending.getLastProviderResult()).isEqualTo(NotificationProviderResult.RETRYABLE);
        assertThat(pending.getLastAttemptRegistrationRevision()).isEqualTo(7);
        assertThat(pending.getLastAttemptAt()).isEqualTo(utc());
    }

    @Test
    @DisplayName("Session 준비 Event는 Alarm LIMIT을 점유하지 않고 gate 미연결 attempt는 차단하며 만료는 전체 source에서 조회한다")
    void persisted_session_delivery_remains_unsendable() {
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        store.apply(expected, decision(expected, 1));
        var delivery = deliveries.findAll().getFirst();
        assertThat(deliveries.findDuePendingIds(utc(), PageRequest.of(0, 1))).isEmpty();
        var handler = mock(com.stopbell.notification.service.NotificationAttemptHandler.class);
        var attempts = new com.stopbell.notification.service.NotificationDispatchAttemptService(deliveries,
                new com.stopbell.notification.service.NotificationRecipientPreflight(devices), handler, manager, clock);
        assertThat(attempts.beginAttempt(delivery.getId())).isEmpty();
        verifyNoInteractions(handler);
        assertThat(deliveries.findById(delivery.getId()).orElseThrow()).usingRecursiveComparison()
                .ignoringFields("notificationEvent", "device").isEqualTo(delivery);
        time.set(NOW.plusSeconds(50));
        assertThat(deliveries.findExpiredPendingIds(utc(), PageRequest.of(0, 1))).containsExactly(delivery.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Event 또는 두 번째 Delivery INSERT 실패는 실제 MySQL transaction의 checkpoint·Event·전체 fan-out을 rollback한다")
    void atomic_rollback_on_insert_failure(boolean eventFailure) {
        device(session.getUser());
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        if (eventFailure) {
            doAnswer(invocation -> {
                var event = (NotificationEvent) invocation.getArgument(0);
                entityManager.persist(event);
                entityManager.flush();
                // An actual FK failure after the Event identity INSERT.
                jdbc.update("update notification_events set user_id=-1 where id=?", event.getId());
                return event;
            }).when(events).saveAndFlush(any(NotificationEvent.class));
        } else {
            var first = new AtomicReference<NotificationDelivery>();
            doAnswer(invocation -> {
                var delivery = (NotificationDelivery) invocation.getArgument(0);
                if (first.get() == null) {
                    first.set(delivery);
                    entityManager.persist(delivery);
                    entityManager.flush();
                    return delivery;
                }
                var duplicate = first.get();
                // Force a real Event x Device UNIQUE violation on the second INSERT.
                return deliveries.saveAndFlush(new NotificationDelivery(duplicate.getNotificationEvent(),
                        duplicate.getDevice(), duplicate.getRecipientOwnershipGeneration(), duplicate.getExpiresAt(), utc()));
            }).when(deliveries).save(any(NotificationDelivery.class));
        }
        assertThatThrownBy(() -> store.apply(expected, decision(expected, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(events.count()).isZero();
        assertThat(deliveries.count()).isZero();
        assertThat(sessions.findById(session.getId()).orElseThrow().getCheckpoint()).isEqualTo(expected.checkpoint());
        assertThat(sessions.findById(session.getId()).orElseThrow().getRevision()).isEqualTo(expected.revision());
        assertThat(runtime.isReady(expected, utc())).isFalse();
    }

    @Test
    @DisplayName("알려진 Event UNIQUE 위반만 rollback 후 새 transaction에서 원래 identity를 확인하고 recipient를 다시 선정하지 않는다")
    void unique_conflict_reconciliation() {
        var expected = store.findOpen().getFirst();
        var candidate = decision(expected, 1);
        var original = new TransactionTemplate(manager).execute(status -> events.saveAndFlush(
                NotificationEvent.forBoardingSession(session.getId(), session.getUser(),
                        BoardingNotificationEventType.PREPARE_ALIGHT, candidate.checkpoint(), utc(), utc(), candidate.expiresAt())));
        device(session.getUser());
        // Model a writer racing a stale absence read. The ensuing UNIQUE is enforced by MySQL.
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> reads.getAndIncrement() == 0 ? Optional.empty() : Optional.of(
                entityManager.find(NotificationEvent.class, original.getId(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)))
                .when(events).findByBoardingSessionIdAndBoardingEventType(session.getId(), BoardingNotificationEventType.PREPARE_ALIGHT);
        assertThat(store.apply(expected, candidate).accepted()).isFalse();
        assertThat(onlyEvent().getId()).isEqualTo(original.getId());
        assertThat(deliveries.count()).isZero();
        assertThat(reads.get()).isEqualTo(2);
        assertThat(store.findOpen().getFirst()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("commit 응답 유실은 새 transaction에서 정확한 checkpoint와 Event ID를 확인하고 rollback이면 어떤 row도 재생성하지 않는다")
    void reconcile_event_after_commit_loss(boolean persisted) {
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        PlatformTransactionManager uncertain = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                if (first.getAndSet(false)) {
                    assertThat(runtime.isReady(expected, utc())).isFalse();
                    if (persisted) { manager.commit(status); } else { manager.rollback(status); }
                    throw new TransactionSystemException("controlled commit acknowledgement loss");
                }
                manager.commit(status);
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        var tested = new BoardingTrackingStore(sessions, metadata, runtime, uncertain, clock, notifications);
        assertThat(tested.apply(expected, decision(expected, 1)).accepted()).isEqualTo(persisted);
        assertThat(events.count()).isEqualTo(persisted ? 1 : 0);
        assertThat(deliveries.count()).isEqualTo(persisted ? 1 : 0);
        if (persisted) {
            var eventId = onlyEvent().getId();
            device(session.getUser());
            assertThat(tested.apply(expected, decision(expected, 1)).accepted()).isFalse();
            assertThat(onlyEvent().getId()).isEqualTo(eventId);
            assertThat(deliveries.count()).isOne();
        } else {
            var latest = store.findOpen().getFirst();
            assertThat(runtime.isReady(latest, utc())).isFalse();
            time.set(NOW.plusSeconds(40));
            var fresh = evaluator.evaluate(latest, BoardingProgressEvaluatorTest.observation(latest, 2,
                    Meaning.VERIFIED_REFERENCE, 40), utc());
            store.apply(latest, fresh);
            assertThat(events.count()).isOne();
            assertThat(deliveries.count()).isOne();
        }
    }

    @Test
    @DisplayName("유효 INSERT 후 commit 지연으로 TTL이 지나도 Event를 보존하며 새 recipient를 만들지 않는다")
    void preserve_insert_after_delayed_commit() {
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        PlatformTransactionManager delayed = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                time.set(NOW.plusSeconds(51));
                manager.commit(status);
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        var tested = new BoardingTrackingStore(sessions, metadata, runtime, delayed, clock, notifications);
        tested.apply(expected, decision(expected, 1));
        assertThat(onlyEvent().getExpiresAt()).isEqualTo(UTC_NOW.plusSeconds(50));
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Session lock 대기 중 취소 또는 TTL 경과는 늦은 Event INSERT를 막고 유효 checkpoint 반영 여부를 구분한다")
    void session_lock_wait_and_expiry(boolean cancel) throws Exception {
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        var candidate = decision(expected, 1);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                var current = sessions.findForUpdate(session.getId()).orElseThrow();
                locked.countDown();
                await(release);
                if (cancel) { current.cancel(current.getRevision(), utc()); }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var pending = pool.submit(() -> store.apply(expected, candidate));
            // The lock is held in a separate actual MySQL connection.
            assertThatThrownBy(() -> pending.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            if (!cancel) { time.set(NOW.plusSeconds(50)); }
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            var result = pending.get(10, TimeUnit.SECONDS);
            assertThat(result.accepted()).isEqualTo(!cancel);
            assertThat(events.count()).isZero();
            assertThat(deliveries.count()).isZero();
            assertThat(sessions.findById(session.getId()).orElseThrow().getCheckpoint())
                    .isEqualTo(cancel ? expected.checkpoint() : candidate.checkpoint());
        } finally { release.countDown(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Device lock을 기다리는 Event 결정은 현재 소유권·enabled를 읽고 lifecycle 취소는 결정 commit 뒤 PENDING을 함께 종료한다")
    void device_registration_and_cancel_race(boolean transfer) throws Exception {
        var recipient = device(session.getUser());
        var other = users.saveAndFlush(new User(AuthProvider.GOOGLE, UUID.randomUUID().toString()));
        var expected = store.findOpen().getFirst();
        var candidate = decision(expected, 1);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var eventInserted = new CountDownLatch(1);
        doAnswer(invocation -> {
            NotificationEvent event = invocation.getArgument(0);
            entityManager.persist(event);
            entityManager.flush();
            eventInserted.countDown();
            return event;
        }).when(events).saveAndFlush(any(NotificationEvent.class));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var registration = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                var current = devices.findByIdForUpdate(recipient.getId()).orElseThrow();
                locked.countDown();
                await(release);
                if (transfer) { current.transferOwnership(other, "moved-target", 1, utc()); }
                else { current.disable(1); }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var pending = pool.submit(() -> store.apply(expected, candidate));
            assertThat(eventInserted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> pending.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            boolean registrationRolledBack = false;
            try {
                registration.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
                registrationRolledBack = true;
            }
            boolean eventRolledBack = false;
            try {
                pending.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
                eventRolledBack = true;
            }
            assertThat(registrationRolledBack && eventRolledBack).isFalse();
            if (eventRolledBack) {
                assertThat(events.count()).isZero();
                assertThat(deliveries.count()).isZero();
                assertThat(store.findOpen().getFirst()).isEqualTo(expected);
            } else if (registrationRolledBack) {
                assertThat(events.count()).isOne();
                assertThat(devices.findById(recipient.getId()).orElseThrow().getUser().getId())
                        .isEqualTo(session.getUser().getId());
                assertThat(deliveries.count()).isOne();
            } else {
                assertThat(events.count()).isOne();
                assertThat(deliveries.count()).isZero();
            }
            var current = sessions.findById(session.getId()).orElseThrow();
            api.cancel(session.getUser().getId(), session.getId(), current.getRevision());
            assertThat(deliveries.findAll()).allSatisfy(value ->
                    assertThat(value.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED));
            assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(BoardingSessionStatus.CANCELLED);
        } finally { release.countDown(); }
    }

    @Test
    @DisplayName("연속성 상실 후 동일 차량 재확인은 기존 Event·recipient·기한과 terminal Delivery를 유지한다")
    void reconfirm_preserves_consumed_event() {
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        store.apply(expected, decision(expected, 1));
        var original = onlyEvent();
        store.apply(store.findOpen().getFirst(), BoardingTrackingDecision.held(BoardingStateReason.VEHICLE_MISSING));
        device(session.getUser());
        time.set(NOW.plusSeconds(40));
        var metadataSnapshot = new BoardingRouteSnapshot(route.getId(), TransitProvider.SEOUL_BUS, "route-id", null,
                traversal.get(3).occurrenceId(), null, traversal.stream().map(item -> new BoardingRouteSnapshot.Occurrence(
                        item.occurrenceId(), item.externalStopId(), item.order(), "정류장")).toList(), "7000");
        var held = store.findOpen().getFirst();
        var baseline = BoardingProgressEvaluatorTest.observation(held, 2, Meaning.VERIFIED_REFERENCE, 40).vehicle();
        var checkpoint = new BoardingCheckpoint(baseline.stopId(), baseline.order(), 2, BoardingCheckpoint.Evidence.ID_ORDER,
                null, UTC_NOW.plusSeconds(40), UTC_NOW.plusSeconds(39));
        when(validator.validate(anyLong(), anyString(), anyString())).thenReturn(new VerifiedBoardingStart(metadataSnapshot,
                expected.traversal(), checkpoint, NOW.plusSeconds(100), expected.vehicleId(), NUMBER, "7000", "목적지"));
        api.reconfirm(session.getUser().getId(), session.getId(),
                new com.stopbell.boarding.dto.ReconfirmBoardingSessionRequest(held.revision(), "new-ref", NUMBER));
        time.set(NOW.plusSeconds(45));
        var current = store.findOpen().getFirst();
        store.apply(current, evaluator.evaluate(current, BoardingProgressEvaluatorTest.observation(current, 2,
                Meaning.VERIFIED_REFERENCE, 45), utc()));
        assertThat(onlyEvent()).usingRecursiveComparison().isEqualTo(original);
        assertThat(deliveries.count()).isOne();
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Event 결정이 Session lock을 먼저 얻으면 취소·수명 만료는 commit까지 기다린 뒤 생성된 PENDING을 종료한다")
    void lifecycle_waits_for_event_commit(boolean expire) throws Exception {
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        var candidate = decision(expected, 1);
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            NotificationEvent event = invocation.getArgument(0);
            entityManager.persist(event);
            entityManager.flush();
            inserted.countDown();
            await(release);
            return event;
        }).when(events).saveAndFlush(any(NotificationEvent.class));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> store.apply(expected, candidate));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            // Uncommitted Event, Session checkpoint and recipients are invisible on another connection.
            assertThat(events.count()).isZero();
            assertThat(sessions.findById(session.getId()).orElseThrow().getRevision()).isZero();
            var terminal = pool.submit(() -> {
                if (expire) { store.expire(expected); }
                else { api.cancel(session.getUser().getId(), session.getId(), 1); }
            });
            assertThatThrownBy(() -> terminal.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            if (expire) { time.set(NOW.plusSeconds(14400)); }
            release.countDown();
            pending.get(10, TimeUnit.SECONDS);
            terminal.get(10, TimeUnit.SECONDS);
            assertThat(events.count()).isOne();
            assertThat(deliveries.findAll()).singleElement().satisfies(value -> assertThat(value.getStatus())
                    .isEqualTo(expire ? NotificationDeliveryStatus.EXPIRED : NotificationDeliveryStatus.FAILED));
            assertThat(sessions.findById(session.getId()).orElseThrow().getStatus())
                    .isEqualTo(expire ? BoardingSessionStatus.EXPIRED : BoardingSessionStatus.CANCELLED);
        } finally { release.countDown(); }
    }

    @Test
    @DisplayName("lifecycle의 Delivery 종료 쓰기 실패는 취소 상태까지 rollback하고 기존 PENDING·Event를 유지한다")
    void rollback_lifecycle_with_delivery_failure() {
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        store.apply(expected, decision(expected, 1));
        var before = store.findOpen().getFirst();
        doAnswer(invocation -> {
            var id = jdbc.queryForObject("select id from notification_deliveries limit 1", Long.class);
            jdbc.update("update notification_deliveries set device_id=-1 where id=?", id);
            return List.of();
        }).when(deliveries).findPendingByBoardingEventIdForUpdate(anyLong());
        assertThatThrownBy(() -> api.cancel(session.getUser().getId(), session.getId(), before.revision()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.findOpen().getFirst()).isEqualTo(before);
        assertThat(events.count()).isOne();
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
    }

    @Test
    @DisplayName("order-only 준비 Event는 실제로 없는 Stop ID·제공시각·GPS를 합성하지 않는다")
    void preserve_order_only_evidence() {
        var expected = store.findOpen().getFirst();
        var observation = BoardingProgressEvaluatorTest.observation(expected, 1, Meaning.VERIFIED_REFERENCE, 20);
        var vehicle = observation.vehicle();
        var orderOnly = new com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle(vehicle.id(), vehicle.number(),
                null, vehicle.order(), 0, vehicle.observedAt(), vehicle.providerDataTime(), null, null);
        var verified = new BoardingProgressObservation(observation.provider(), observation.routeId(), observation.cityCode(),
                orderOnly, Meaning.VERIFIED_REFERENCE, true, null, false);
        store.apply(expected, evaluator.evaluate(expected, verified, utc()));
        assertThat(onlyEvent().getCurrentStopExternalId()).isNull();
        assertThat(onlyEvent().getCurrentStopOrder()).isEqualTo(vehicle.order());
        assertThat(onlyEvent().getCurrentStopName()).isNull();
        assertThat(onlyEvent().getLatitude()).isNull();
        assertThat(onlyEvent().getLongitude()).isNull();
    }

    @Test
    @DisplayName("새 생성 요청의 기존 current 세션 만료도 이전 Session·Delivery를 함께 종료하고 새 Session과 Event를 혼동하지 않는다")
    void new_creation_expires_previous_session() {
        device(session.getUser());
        var expected = store.findOpen().getFirst();
        store.apply(expected, decision(expected, 1));
        var original = onlyEvent();
        time.set(NOW.plusSeconds(14400));
        var metadataSnapshot = new BoardingRouteSnapshot(route.getId(), TransitProvider.SEOUL_BUS, "route-id", null,
                traversal.get(3).occurrenceId(), null, traversal.stream().map(item -> new BoardingRouteSnapshot.Occurrence(
                        item.occurrenceId(), item.externalStopId(), item.order(), "정류장")).toList(), "7000");
        var checkpoint = new BoardingCheckpoint("s2", traversal.get(2).order(), 2, BoardingCheckpoint.Evidence.ID_ORDER,
                null, utc(), utc().minusSeconds(1));
        when(validator.validate(anyLong(), anyString(), anyString())).thenReturn(new VerifiedBoardingStart(metadataSnapshot,
                expected.traversal(), checkpoint, time.get().plusSeconds(60), expected.vehicleId(), NUMBER, "7000", "목적지"));
        var created = api.create(session.getUser().getId(), new com.stopbell.boarding.dto.CreateBoardingSessionRequest(
                UUID.randomUUID().toString(), "new-ref", NUMBER));
        assertThat(created.created()).isTrue();
        assertThat(created.response().id()).isNotEqualTo(session.getId());
        assertThat(created.response().status()).isEqualTo(BoardingSessionStatus.TRACKING);
        assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(BoardingSessionStatus.EXPIRED);
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(onlyEvent().getId()).isEqualTo(original.getId());
    }

    @Test
    @DisplayName("Session 취소는 같은 User의 Alarm Event·PENDING·TTL·attempt를 변경하지 않고 Alarm의 LIMIT 조회도 보존한다")
    void session_lifecycle_preserves_alarm_delivery() {
        var recipient = device(session.getUser());
        var alarmDelivery = new TransactionTemplate(manager).execute(status -> {
            var alarm = new Alarm(session.getUser(), new BusAlarmTarget(TransitProvider.SEOUL_BUS, "route-id", "s3", 90,
                    "7000", "목적지", null, null, null, null, null));
            alarm.activate();
            entityManager.persist(alarm);
            entityManager.flush();
            var evidence = new com.stopbell.transit.domain.TransitEvent(com.stopbell.transit.domain.TransitEventType.ARRIVED,
                    "vehicle-id", UUID.randomUUID(), time.get(), "s3", "목적지", 90, null, null, null, null);
            var event = events.saveAndFlush(new NotificationEvent(alarm.getId(), session.getUser(), 1, evidence, utc(), utc()));
            return deliveries.saveAndFlush(new NotificationDelivery(event, recipient, 0, utc().plusSeconds(60), utc()));
        });
        var expected = store.findOpen().getFirst();
        store.apply(expected, decision(expected, 1));
        assertThat(deliveries.findDuePendingIds(utc(), PageRequest.of(0, 1))).containsExactly(alarmDelivery.getId());
        api.cancel(session.getUser().getId(), session.getId(), 1);
        assertThat(deliveries.findById(alarmDelivery.getId()).orElseThrow()).usingRecursiveComparison()
                .ignoringFields("notificationEvent", "device").isEqualTo(alarmDelivery);
        assertThat(events.findById(alarmDelivery.getNotificationEvent().getId()).orElseThrow().getSourceType())
                .isEqualTo(NotificationSourceType.ALARM);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Controlled lock timeout"); }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Device device(User owner) {
        return devices.saveAndFlush(new Device(owner, UUID.randomUUID().toString(), "a".repeat(64),
                DevicePlatform.IOS, "target-" + UUID.randomUUID(), 0, utc()));
    }

    private NotificationEvent onlyEvent() {
        assertThat(events.findAll()).hasSize(1);
        return events.findAll().getFirst();
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
