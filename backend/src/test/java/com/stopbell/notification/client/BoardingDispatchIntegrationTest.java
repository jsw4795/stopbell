package com.stopbell.notification.client;

import static com.stopbell.boarding.BoardingSessionFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.stopbell.boarding.domain.BoardingTrackingSnapshot;
import com.stopbell.boarding.entity.*;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.boarding.service.*;
import com.stopbell.notification.entity.*;
import com.stopbell.notification.repository.*;
import com.stopbell.notification.service.*;
import com.stopbell.transit.domain.*;
import com.stopbell.user.entity.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "notification.dispatch.enabled=false",
        "notification.fcm.enabled=false"})
@ActiveProfiles("test")
@Testcontainers
class BoardingDispatchIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private BoardingSessionRepository sessions;
    @MockitoSpyBean private NotificationDeliveryRepository deliveries;
    @MockitoSpyBean private DeviceRepository devices;
    @Autowired private NotificationEventRepository events;
    @Autowired private BoardingTrackingRuntime runtime;
    @Autowired private BoardingSessionService api;
    @Autowired private BoardingNotificationService notifications;
    @Autowired private PlatformTransactionManager manager;
    @MockitoBean(name = "transitMetadataClock") private Clock clock;
    private final AtomicReference<Instant> time = new AtomicReference<>();
    private NotificationDispatchAttemptService attempts;
    private DurableNotificationAttemptHandler handler;
    private FcmProviderClient provider;

    @BeforeEach
    void set_up() {
        runtime.admittedIds().forEach(runtime::forget);
        jdbc.update("delete from notification_deliveries");
        jdbc.update("delete from notification_events");
        jdbc.update("delete from devices");
        jdbc.update("delete from boarding_sessions");
        time.set(NOW);
        when(clock.instant()).thenAnswer(call -> time.get());
        handler = spy(new DurableNotificationAttemptHandler(new NotificationRetryPolicy(new NotificationRetryProperties(
                3, Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ZERO, Duration.ofSeconds(10)),
                new FcmClientProperties(true, "test-project", "/unused", Duration.ofSeconds(1),
                        Duration.ofSeconds(2), Duration.ofSeconds(15))), devices, clock));
        attempts = service(manager, runtime);
        provider = mock(FcmProviderClient.class);
        when(provider.send(any())).thenAnswer(call -> result(NotificationProviderResult.ACCEPTED));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("완료와 원자 저장된 목적지 알림은 재시작 후에도 검증된 완료 근거로 한 번만 전송한다")
    void destination_dispatch_survives_restart(boolean restart) {
        var fixture = fixture(false);
        time.set(NOW.plusSeconds(1));
        Long destinationId = completeDestination(fixture);
        var gate = restart ? new BoardingTrackingRuntime() : runtime;
        var service = service(manager, gate);
        var dispatch = new NotificationDispatchWorker(new NotificationDispatchProperties(true, 5, Duration.ofSeconds(1)),
                deliveries, service, provider, clock, gate);
        dispatch.dispatchPending();
        dispatch.dispatchPending();
        verify(provider, times(1)).send(argThat(request -> request.source().boardingEventType() == BoardingNotificationEventType.DESTINATION_REACHED));
        assertThat(deliveries.findAllByNotificationEventIdOrderByIdAsc(destinationId)).singleElement()
                .extracting(NotificationDelivery::getStatus).isEqualTo(NotificationDeliveryStatus.ACCEPTED);
        assertThat(row(fixture).getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    @DisplayName("완료 목적지 알림도 기한·관측 freshness·Event 대응·완료 상태가 틀리면 FCM을 호출하지 않는다")
    void reject_invalid_destination_evidence(int scenario) {
        var fixture = fixture(false);
        time.set(NOW.plusSeconds(1));
        Long eventId = completeDestination(fixture);
        switch (scenario) {
            case 0 -> time.set(NOW.plusSeconds(31));
            case 1 -> jdbc.update("update notification_events set current_stop_order=999 where id=?", eventId);
            case 2 -> jdbc.update("update boarding_sessions set checkpoint_provider_data_time=? where id=?", UTC_NOW.minusSeconds(61), fixture.sessionId());
            case 3 -> jdbc.update("update notification_events set provider_data_time=? where id=?", UTC_NOW.minusSeconds(1), eventId);
            case 4 -> jdbc.update("update boarding_sessions set checkpoint_index=0 where id=?", fixture.sessionId());
        }
        worker(attempts, provider, 5).dispatchPending();
        verifyNoInteractions(provider);
        assertThat(deliveries.findAllByNotificationEventIdOrderByIdAsc(eventId)).singleElement()
                .extracting(NotificationDelivery::getAttemptCount).isEqualTo(0);
    }

    @Test
    @DisplayName("검증된 fixture는 실제 Worker·commit·HTTP v1을 거쳐 원래 Session payload와 기한으로 ACCEPTED를 저장한다")
    void worker_to_loopback_fcm() throws Exception {
        var fixture = fixture(true);
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/send", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var started = row(fixture);
            assertThat(started.getAttemptCount()).isOne();
            byte[] response = "{\"name\":\"projects/fixture/messages/session\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var fcm = new FcmProviderClient(new FcmClientProperties(true, "test-project", "/unused",
                    Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5)), clock,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/send"), attempt -> "synthetic-token");
            worker(attempts, fcm, 5).dispatchPending();
            var data = JsonParser.parseString(body.get()).getAsJsonObject().getAsJsonObject("message").getAsJsonObject("data");
            assertThat(data.keySet()).containsExactlyInAnyOrder("type", "boardingSessionId", "eventType", "notificationEventId");
            assertThat(data.get("boardingSessionId").getAsString()).isEqualTo(Long.toString(fixture.sessionId()));
            assertThat(row(fixture).getStatus()).isEqualTo(NotificationDeliveryStatus.ACCEPTED);
            assertThat(row(fixture).getExpiresAt()).isEqualTo(UTC_NOW.plusSeconds(30));
            // ACCEPTED is only Provider acceptance; this fixture has no iPhone.
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Session attempt는 current Session·Delivery·Device 순서로 잠그고 결과는 Session을 다시 잠그지 않는다")
    void lock_order_and_exact_attempt() {
        var fixture = fixture(true);
        clearInvocations(sessions, deliveries, devices);
        var snapshot = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        var order = inOrder(sessions, deliveries, devices);
        order.verify(deliveries).findSourceIdentity(fixture.deliveryId());
        order.verify(sessions).findForUpdate(fixture.sessionId());
        order.verify(deliveries).findByIdForUpdate(fixture.deliveryId());
        order.verify(devices).findByIdForUpdate(fixture.deviceId());
        assertThat(snapshot.target().validatedSession().revision()).isZero();
        assertThat(snapshot.target().notificationEventId()).isEqualTo(fixture.eventId());
        assertThat(snapshot.target().ownershipGeneration()).isZero();
        assertThat(row(fixture).getNextAttemptAt()).isEqualTo(UTC_NOW.plusSeconds(25));
        assertThat(row(fixture).getAttemptCount()).isOne();
        clearInvocations(sessions);
        attempts.completeAttempt(snapshot, result(NotificationProviderResult.INVALID_TARGET));
        verifyNoInteractions(sessions);
        assertThat(devices.findById(fixture.deviceId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Alarm attempt는 기존 Delivery·Device 순서를 유지하고 Session 조회와 lock을 추가하지 않는다")
    void alarm_retains_original_lock_order() {
        long id = alarmDelivery();
        clearInvocations(sessions, deliveries, devices);
        var snapshot = attempts.beginAttempt(id).orElseThrow();
        var order = inOrder(deliveries, devices);
        order.verify(deliveries).findSourceIdentity(id);
        order.verify(deliveries).findByIdForUpdate(id);
        order.verify(devices).findByIdForUpdate(snapshot.target().deviceId());
        verifyNoInteractions(sessions);
        assertThat(snapshot.target().source().sourceType()).isEqualTo(NotificationSourceType.ALARM);
    }

    enum GateFailure { RECOVERY_PENDING, ADMITTED_ONLY, SUSPENDED, CANCELLED, COMPLETED, EXPIRED,
        MISSING, OWNER, REVISION, CHECKPOINT, STALE, FUTURE_PROVIDER, MISSING_PROVIDER, AFTER_TARGET }

    @ParameterizedTest
    @EnumSource(GateFailure.class)
    @DisplayName("DB lifecycle·owner·current revision·checkpoint·freshness가 검증과 다르면 attempt와 외부 호출을 차단한다")
    void fail_closed_gate(GateFailure failure) {
        var fixture = fixture(true);
        switch (failure) {
            case RECOVERY_PENDING -> runtime.forget(fixture.sessionId());
            case ADMITTED_ONLY -> { runtime.forget(fixture.sessionId()); runtime.admitCreated(fixture.sessionId()); }
            case MISSING -> jdbc.update("delete from boarding_sessions where id=?", fixture.sessionId());
            case OWNER -> jdbc.update("update notification_events set user_id=? where id=?", otherUser(), fixture.eventId());
            case REVISION -> jdbc.update("update boarding_sessions set revision=revision+1 where id=?", fixture.sessionId());
            case CHECKPOINT -> jdbc.update("update boarding_sessions set checkpoint_observed_at=? where id=?",
                    UTC_NOW.minusSeconds(1), fixture.sessionId());
            case STALE -> time.set(NOW.plusSeconds(61));
            case FUTURE_PROVIDER, MISSING_PROVIDER, AFTER_TARGET -> {
                var original = snapshot(fixture);
                var checkpoint = failure == GateFailure.AFTER_TARGET
                        ? new BoardingCheckpoint("s3", 30, 2, BoardingCheckpoint.Evidence.ID_ORDER, null, UTC_NOW, UTC_NOW)
                        : new BoardingCheckpoint("s1", 10, 0, BoardingCheckpoint.Evidence.ID_ORDER, null,
                            failure == GateFailure.FUTURE_PROVIDER ? UTC_NOW.plusSeconds(1) : UTC_NOW,
                            failure == GateFailure.MISSING_PROVIDER ? null : UTC_NOW.plusSeconds(1));
                var current = new BoardingTrackingSnapshot(original.id(), original.revision(), original.status(), original.reason(),
                        original.provider(), original.routeId(), original.cityCode(), original.vehicleId(), original.vehicleNumber(),
                        original.traversal(), checkpoint, original.expiresAt());
                runtime.forget(fixture.sessionId());
                runtime.verified(current);
                assertThat(runtime.isReady(current, UTC_NOW)).isFalse();
            }
            default -> terminate(fixture, failure);
        }
        assertThat(attempts.beginAttempt(fixture.deliveryId())).isEmpty();
        assertThat(row(fixture).getAttemptCount()).isZero();
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("재시작은 이전 TRACKING을 차단하고 새 검증 commit의 current revision으로 원래 Event의 전송을 허용한다")
    void recovery_uses_current_revision_not_event_revision() {
        var fixture = fixture(true);
        var freshRuntime = new BoardingTrackingRuntime();
        var restarted = service(manager, freshRuntime);
        assertThat(restarted.beginAttempt(fixture.deliveryId())).isEmpty();
        time.set(NOW.plusSeconds(1));
        var latest = updateCheckpoint(fixture, 1);
        freshRuntime.verified(latest);
        var snapshot = restarted.beginAttempt(fixture.deliveryId()).orElseThrow();
        assertThat(snapshot.target().validatedSession().revision()).isEqualTo(1);
        assertThat(snapshot.target().notificationEventId()).isEqualTo(fixture.eventId());
        assertThat(snapshot.target().expiresAt()).isEqualTo(UTC_NOW.plusSeconds(30));
        assertThat(events.count()).isOne();
        assertThat(deliveries.count()).isOne();
    }

    enum AfterStart { CANCEL, SUSPEND, COMPLETE, REVISION, CHECKPOINT, CLOSE, DEADLINE }

    @ParameterizedTest
    @EnumSource(AfterStart.class)
    @DisplayName("시작 commit 후 gate·revision·checkpoint·기한 변경은 FCM을 생략하고 소비한 count와 실제 결과를 보존한다")
    void closes_between_commit_and_send(AfterStart change) {
        var fixture = fixture(true);
        var wrapped = spy(attempts);
        doAnswer(call -> {
            var snapshot = attempts.beginAttempt(fixture.deliveryId());
            switch (change) {
                case CANCEL -> api.cancel(fixture.ownerId(), fixture.sessionId(), 0);
                case SUSPEND -> terminate(fixture, GateFailure.SUSPENDED);
                case COMPLETE -> terminate(fixture, GateFailure.COMPLETED);
                case REVISION, CHECKPOINT -> { time.set(NOW.plusSeconds(1)); runtime.verified(updateCheckpoint(fixture, 1)); }
                case CLOSE -> runtime.committed(fixture.sessionId(), 0, false);
                case DEADLINE -> time.set(NOW.plusSeconds(30));
            }
            return snapshot;
        }).when(wrapped).beginAttempt(fixture.deliveryId());
        worker(wrapped, provider, 5).dispatchPending();
        verifyNoInteractions(provider);
        verify(wrapped, never()).completeAttempt(any(), any());
        assertThat(row(fixture).getAttemptCount()).isOne();
        assertThat(row(fixture).getLastProviderResult()).isNull();
        if (change == AfterStart.SUSPEND || change == AfterStart.REVISION || change == AfterStart.CHECKPOINT || change == AfterStart.CLOSE) {
            assertThat(row(fixture).getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
            assertThat(row(fixture).getNextAttemptAt()).isEqualTo(UTC_NOW.plusSeconds(25));
            time.set(NOW.plusSeconds(30));
            attempts.expireDuePending(fixture.deliveryId());
            assertThat(row(fixture).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("시작 rollback과 commit 응답 유실 모두 Provider를 차단하고 실제 commit 여부에 맞는 count만 남긴다")
    void uncertain_start_never_sends(boolean committed) {
        var fixture = fixture(true);
        worker(service(failingCommit(committed), runtime), provider, 5).dispatchPending();
        verifyNoInteractions(provider);
        assertThat(row(fixture).getAttemptCount()).isEqualTo(committed ? 1 : 0);
        assertThat(attempts.beginAttempt(fixture.deliveryId()).isPresent()).isEqualTo(!committed);
    }

    @ParameterizedTest
    @EnumSource(NotificationProviderResult.class)
    @DisplayName("Session은 기존 여섯 결과·retry·Retry-After·기한 cap과 cleanup 정책을 그대로 재사용한다")
    void same_durable_result_policy(NotificationProviderResult kind) {
        var fixture = fixture(true);
        when(provider.send(any())).thenReturn(result(kind));
        worker(attempts, provider, 5).dispatchPending();
        var row = row(fixture);
        assertThat(row.getAttemptCount()).isOne();
        assertThat(row.getLastProviderResult()).isEqualTo(kind);
        assertThat(row.getExpiresAt()).isEqualTo(UTC_NOW.plusSeconds(30));
        assertThat(row.getStatus()).isEqualTo(switch (kind) {
            case ACCEPTED -> NotificationDeliveryStatus.ACCEPTED;
            case RETRYABLE, AMBIGUOUS_TIMEOUT -> NotificationDeliveryStatus.PENDING;
            default -> NotificationDeliveryStatus.FAILED;
        });
        if (row.getStatus() == NotificationDeliveryStatus.PENDING) {
            assertThat(row.getNextAttemptAt()).isEqualTo(row.getExpiresAt());
        }
        verify(provider, times(1)).send(any());
    }

    @Test
    @DisplayName("gate가 없는 만료 Session은 미래 retry 예약과 이전 결과 및 count를 보존해 EXPIRED로 정리한다")
    void expiry_ignores_readiness_and_future_schedule() {
        var fixture = fixture(true);
        var snapshot = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        attempts.completeAttempt(snapshot, result(NotificationProviderResult.RETRYABLE));
        runtime.forget(fixture.sessionId());
        time.set(NOW.plusSeconds(30));
        // The existing database CHECK rejects a reservation beyond the immutable deadline.
        assertThatThrownBy(() -> jdbc.update("update notification_deliveries set next_attempt_at=? where id=?",
                UTC_NOW.plusHours(1), fixture.deliveryId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        worker(attempts, provider, 1).dispatchPending();
        var expired = row(fixture);
        assertThat(expired.getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(expired.getLastFailureCode()).isEqualTo("FRESHNESS_EXPIRED");
        assertThat(expired.getLastProviderResult()).isEqualTo(NotificationProviderResult.RETRYABLE);
        assertThat(expired.getAttemptCount()).isOne();
        verifyNoInteractions(provider);
        attempts.completeAttempt(snapshot, result(NotificationProviderResult.ACCEPTED));
        assertThat(row(fixture).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Provider가 이미 시작된 뒤 취소·만료가 먼저 종료한 Delivery는 늦은 응답으로 되살아나지 않는다")
    void cancellation_during_provider_is_terminal(boolean expired) {
        var fixture = fixture(true);
        when(provider.send(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (expired) {
                time.set(NOW.plusSeconds(14400));
                api.find(fixture.ownerId(), fixture.sessionId());
            } else {
                api.cancel(fixture.ownerId(), fixture.sessionId(), 0);
            }
            return result(NotificationProviderResult.ACCEPTED);
        });
        worker(attempts, provider, 5).dispatchPending();
        assertThat(row(fixture).getStatus()).isEqualTo(expired ? NotificationDeliveryStatus.EXPIRED : NotificationDeliveryStatus.FAILED);
        assertThat(row(fixture).getAttemptCount()).isOne();
        verify(provider, times(1)).send(any());
        worker(attempts, provider, 5).dispatchPending();
        verifyNoMoreInteractions(provider);
    }

    @Test
    @DisplayName("수천 개 미복구·보류 Delivery는 SQL LIMIT을 점유하지 않고 두 source의 독립 batch 예산을 유지한다")
    void backlog_fairness_and_sql_plan() {
        var blocked = fixture(false);
        // Distinct persisted Sessions/Events cover both recovery-pending and suspended backlogs.
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < 2000; i++) {
                var user = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
                entityManager.persist(user);
                var session = session(user);
                if (i % 2 == 0) {
                    session.suspend(0, BoardingStateReason.TEMPORARY_PROVIDER_FAILURE, UTC_NOW);
                }
                entityManager.persist(session);
                var device = new Device(user, UUID.randomUUID().toString(), "a".repeat(64), DevicePlatform.IOS,
                        "backlog-fid-" + i, 0, UTC_NOW);
                entityManager.persist(device);
                var event = NotificationEvent.forBoardingSession(session.getId(), user,
                        BoardingNotificationEventType.PREPARE_ALIGHT, session.getCheckpoint(), UTC_NOW, UTC_NOW,
                        UTC_NOW.plusSeconds(30));
                entityManager.persist(event);
                entityManager.persist(new NotificationDelivery(event, device, 0, event.getExpiresAt(), UTC_NOW));
            }
        });
        var held = fixture(false);
        terminate(held, GateFailure.SUSPENDED);
        var ready = fixture(true);
        var alarm = alarmDelivery();
        clearInvocations(deliveries);
        worker(attempts, provider, 1).dispatchPending();
        assertThat(row(ready).getStatus()).isEqualTo(NotificationDeliveryStatus.ACCEPTED);
        assertThat(deliveries.findById(alarm).orElseThrow().getStatus()).isEqualTo(NotificationDeliveryStatus.ACCEPTED);
        assertThat(row(blocked).getAttemptCount()).isZero();
        assertThat(row(held).getAttemptCount()).isZero();
        var page = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(deliveries).findDuePendingIds(eq(UTC_NOW), page.capture());
        assertThat(page.getValue().getPageSize()).isOne();
        verify(deliveries).findBoardingDuePendingIds(eq(List.of(ready.sessionId())), eq(UTC_NOW), any());
        var sent = org.mockito.ArgumentCaptor.forClass(NotificationSendRequest.class);
        verify(provider, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(value -> value.source().sourceType())
                .containsExactly(NotificationSourceType.ALARM, NotificationSourceType.BOARDING_SESSION);
        var plan = jdbc.queryForList("""
                explain select d.id from notification_events e
                join boarding_sessions s on s.id=e.boarding_session_id
                join notification_deliveries d on d.notification_event_id=e.id
                where e.source_type='BOARDING_SESSION' and e.boarding_event_type='PREPARE_ALIGHT'
                  and s.id in (?) and s.status='TRACKING' and s.expires_at>?
                  and s.user_id=e.user_id and d.status='PENDING' and d.next_attempt_at<=? and d.expires_at>?
                order by d.next_attempt_at,d.id limit 1
                """, ready.sessionId(), UTC_NOW, UTC_NOW, UTC_NOW);
        assertThat(plan).allSatisfy(item -> assertThat(item.get("type")).isNotEqualTo("ALL"));
        System.out.println("Session due EXPLAIN: " + plan);
        var expiryPlan = jdbc.queryForList("""
                explain select d.id from notification_deliveries d join notification_events e on e.id=d.notification_event_id
                where e.source_type='ALARM' and d.status='PENDING' and d.expires_at<=?
                order by d.expires_at,d.id limit 1
                """, UTC_NOW.plusSeconds(30));
        System.out.println("Expiry EXPLAIN (no expiry index added): " + expiryPlan);
    }

    @Test
    @DisplayName("CONFIGURATION 차단 중에도 두 source의 만료를 별도 bounded 예산으로 처리한다")
    void configuration_blocked_expiry_fairness() {
        var first = fixture(true);
        var second = fixture(false);
        var alarm = alarmDelivery();
        when(provider.send(any())).thenReturn(result(NotificationProviderResult.CONFIGURATION));
        var worker = worker(attempts, provider, 1);
        worker.dispatchPending();
        verify(provider, times(1)).send(any());
        time.set(NOW.plusSeconds(60));
        worker.dispatchPending();
        assertThat(row(first).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(row(second).getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        worker.dispatchPending();
        assertThat(row(second).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(deliveries.findById(alarm).orElseThrow().getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        verifyNoMoreInteractions(provider);
    }

    enum BeforeStart { CANCEL, EXPIRE, POLL, RECONFIRM }

    @ParameterizedTest
    @EnumSource(BeforeStart.class)
    @DisplayName("실제 MySQL에서 Session 변경이 먼저 잠그면 대기 attempt는 최신 상태와 gate만 읽는다")
    void lifecycle_wins_session_lock(BeforeStart change) throws Exception {
        var fixture = fixture(true);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var lifecycle = Thread.startVirtualThread(() -> {
            try {
                tx().executeWithoutResult(status -> {
                    var session = sessions.findForUpdate(fixture.sessionId()).orElseThrow();
                    runtime.committed(session.getId(), session.getRevision(), false);
                    locked.countDown();
                    await(release);
                    switch (change) {
                        case CANCEL -> session.cancel(0, utc());
                        case EXPIRE -> { time.set(NOW.plusSeconds(14400)); session.expire(utc()); }
                        case POLL -> {
                            time.set(NOW.plusSeconds(1));
                            session.track(0, new BoardingCheckpoint("s2", 20, 1, BoardingCheckpoint.Evidence.ID_ORDER,
                                    null, utc(), utc()), null, utc());
                        }
                        case RECONFIRM -> {
                            session.suspend(0, BoardingStateReason.VEHICLE_MISSING, utc());
                            session.reconfirm(1, session.getCheckpoint(), utc());
                        }
                    }
                    notifications.terminatePending(session);
                });
            } catch (Throwable exception) { failure.set(exception); }
        });
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        var result = new AtomicReference<Optional<NotificationAttemptSnapshot>>();
        var attempting = Thread.startVirtualThread(() -> {
            try { result.set(attempts.beginAttempt(fixture.deliveryId())); }
            catch (Throwable exception) { failure.set(exception); }
        });
        release.countDown();
        lifecycle.join(5000);
        attempting.join(5000);
        assertThat(lifecycle.isAlive()).isFalse();
        assertThat(attempting.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(result.get()).isEmpty();
        assertThat(row(fixture).getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("attempt가 Session을 먼저 잠그면 취소는 순서대로 기다리고 뒤늦은 결과와 readiness는 종료를 되돌리지 못한다")
    void attempt_wins_then_cancellation() throws Exception {
        var fixture = fixture(true);
        var old = snapshot(fixture);
        var deliveryLocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            deliveryLocked.countDown();
            await(release);
            return call.callRealMethod();
        }).when(handler).checkEligibility(any(), any());
        var result = new AtomicReference<NotificationAttemptSnapshot>();
        var failure = new AtomicReference<Throwable>();
        var attempting = Thread.startVirtualThread(() -> {
            try { result.set(attempts.beginAttempt(fixture.deliveryId()).orElseThrow()); }
            catch (Throwable exception) { failure.set(exception); }
        });
        assertThat(deliveryLocked.await(5, TimeUnit.SECONDS)).isTrue();
        var cancelling = Thread.startVirtualThread(() -> {
            try { api.cancel(fixture.ownerId(), fixture.sessionId(), 0); }
            catch (Throwable exception) { failure.set(exception); }
        });
        release.countDown();
        attempting.join(5000);
        cancelling.join(5000);
        assertThat(attempting.isAlive()).isFalse();
        assertThat(cancelling.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        runtime.verified(old);
        assertThat(attempts.allowsBeforeSend(result.get())).isFalse();
        attempts.completeAttempt(result.get(), result(NotificationProviderResult.ACCEPTED));
        assertThat(row(fixture).getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(row(fixture).getAttemptCount()).isOne();
    }

    @Test
    @DisplayName("외부 transaction의 오래된 REPEATABLE READ snapshot과 source 사전 조회는 새 Session locking read를 대신하지 않는다")
    void repeatable_read_and_stale_hint() throws Exception {
        var fixture = fixture(true);
        tx().executeWithoutResult(status -> {
            assertThat(sessions.findById(fixture.sessionId()).orElseThrow().getStatus()).isEqualTo(BoardingSessionStatus.TRACKING);
            var cancelling = Thread.startVirtualThread(() -> api.cancel(fixture.ownerId(), fixture.sessionId(), 0));
            try { cancelling.join(5000); } catch (InterruptedException exception) { throw new AssertionError(exception); }
            assertThat(cancelling.isAlive()).isFalse();
            assertThat(attempts.beginAttempt(fixture.deliveryId())).isEmpty();
        });
        assertThat(row(fixture).getAttemptCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Device 소유권 이전과 등록 교체는 current preflight 및 늦은 cleanup의 revision·generation 경계를 지킨다")
    void device_transfer_and_registration(boolean transfer) {
        var fixture = fixture(true);
        var old = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        long nextOwner = otherUser();
        tx().executeWithoutResult(status -> {
            var device = devices.findByIdForUpdate(fixture.deviceId()).orElseThrow();
            if (transfer) {
                device.transferOwnership(entityManager.find(User.class, nextOwner), "new-fid", 1, utc());
            } else {
                device.register("new-fid", 1, utc());
            }
        });
        attempts.completeAttempt(old, result(NotificationProviderResult.INVALID_TARGET));
        assertThat(devices.findById(fixture.deviceId()).orElseThrow().isEnabled()).isTrue();
        assertThat(devices.findById(fixture.deviceId()).orElseThrow().getCurrentPushTargetId()).isEqualTo("new-fid");
        var another = fixture(true);
        tx().executeWithoutResult(status -> devices.findByIdForUpdate(another.deviceId()).orElseThrow()
                .transferOwnership(entityManager.find(User.class, nextOwner), "other-fid", 1, utc()));
        assertThat(attempts.beginAttempt(another.deliveryId())).isEmpty();
        assertThat(row(another).getLastFailureCode()).isEqualTo("RECIPIENT_OWNER_CHANGED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("결과 transaction rollback 또는 commit 응답 유실은 같은 결과만 제한 재적용하고 FCM을 다시 호출하지 않는다")
    void result_database_failure_never_resends(boolean committed) {
        var fixture = fixture(true);
        var commits = new java.util.concurrent.atomic.AtomicInteger();
        PlatformTransactionManager tested = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                if (commits.incrementAndGet() == 2) {
                    if (committed) { manager.commit(status); } else { manager.rollback(status); }
                    throw new TransactionSystemException("synthetic result commit failure");
                }
                manager.commit(status);
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        worker(service(tested, runtime), provider, 5).dispatchPending();
        assertThat(row(fixture).getStatus()).isEqualTo(NotificationDeliveryStatus.ACCEPTED);
        assertThat(row(fixture).getAttemptCount()).isOne();
        verify(provider, times(1)).send(any());
        verify(handler, times(committed ? 1 : 2)).completeAttempt(any(), any(), any());
    }

    @Test
    @DisplayName("recovery 다음 attempt 이후 과거 결과는 새 attempt와 현재 registration을 변경하지 않는다")
    void stale_result_after_recovery() {
        var fixture = fixture(true);
        var old = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        time.set(NOW.plusSeconds(25));
        runtime.verified(updateCheckpoint(fixture, 1));
        var current = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        assertThat(current.attemptCount()).isEqualTo(2);
        attempts.completeAttempt(old, result(NotificationProviderResult.INVALID_TARGET));
        assertThat(row(fixture).getLastProviderResult()).isNull();
        assertThat(devices.findById(fixture.deviceId()).orElseThrow().isEnabled()).isTrue();
        attempts.completeAttempt(current, result(NotificationProviderResult.ACCEPTED));
        assertThat(row(fixture).getStatus()).isEqualTo(NotificationDeliveryStatus.ACCEPTED);
        assertThat(row(fixture).getExpiresAt()).isEqualTo(UTC_NOW.plusSeconds(30));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Device lock 대기 중 이전 또는 재등록이 commit되면 현재 owner·generation 또는 새 registration만 읽는다")
    void device_lock_race(boolean transfer) throws Exception {
        var fixture = fixture(true);
        long nextOwner = otherUser();
        var locked = new CountDownLatch(1);
        var checking = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var registration = Thread.startVirtualThread(() -> {
            try {
                tx().executeWithoutResult(status -> {
                    var device = devices.findByIdForUpdate(fixture.deviceId()).orElseThrow();
                    locked.countDown();
                    await(release);
                    if (transfer) {
                        device.transferOwnership(entityManager.find(User.class, nextOwner), "raced-fid", 1, utc());
                    } else {
                        device.register("raced-fid", 1, utc());
                    }
                });
            } catch (Throwable exception) { failure.set(exception); }
        });
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        var observedDevices = mock(DeviceRepository.class, org.mockito.AdditionalAnswers.delegatesTo(devices));
        doAnswer(call -> {
            checking.countDown();
            return devices.findByIdForUpdate(fixture.deviceId());
        }).when(observedDevices).findByIdForUpdate(fixture.deviceId());
        var tested = new NotificationDispatchAttemptService(deliveries, new NotificationRecipientPreflight(observedDevices),
                handler, manager, clock, sessions, runtime);
        var result = new AtomicReference<Optional<NotificationAttemptSnapshot>>();
        var attempting = Thread.startVirtualThread(() -> {
            try { result.set(tested.beginAttempt(fixture.deliveryId())); }
            catch (Throwable exception) { failure.set(exception); }
        });
        assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        registration.join(5000);
        attempting.join(5000);
        assertThat(registration.isAlive()).isFalse();
        assertThat(attempting.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        if (transfer) {
            assertThat(result.get()).isEmpty();
            assertThat(row(fixture).getAttemptCount()).isZero();
            assertThat(row(fixture).getLastFailureCode()).isEqualTo("RECIPIENT_OWNER_CHANGED");
        } else {
            assertThat(result.get().orElseThrow().target().fid()).isEqualTo("raced-fid");
            assertThat(result.get().orElseThrow().target().registrationRevision()).isOne();
        }
    }

    @Test
    @DisplayName("Device lock 대기 중 TTL이 끝나면 변경된 recipient보다 만료를 우선하고 attempt를 소비하지 않는다")
    void expiry_while_waiting_for_device() {
        var fixture = fixture(true);
        var delayedDevices = mock(DeviceRepository.class, org.mockito.AdditionalAnswers.delegatesTo(devices));
        doAnswer(call -> {
            time.set(NOW.plusSeconds(30));
            return devices.findByIdForUpdate(fixture.deviceId());
        }).when(delayedDevices).findByIdForUpdate(fixture.deviceId());
        var tested = new NotificationDispatchAttemptService(deliveries, new NotificationRecipientPreflight(delayedDevices),
                handler, manager, clock, sessions, runtime);
        assertThat(tested.beginAttempt(fixture.deliveryId())).isEmpty();
        assertThat(row(fixture).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(row(fixture).getAttemptCount()).isZero();
        assertThat(row(fixture).getLastFailureCode()).isEqualTo("FRESHNESS_EXPIRED");
    }

    @Test
    @DisplayName("잠금 전 source projection과 current Delivery의 Event identity가 다르면 Device lock 이전에 차단한다")
    void stale_source_hint_is_rechecked() {
        var fixture = fixture(true);
        var hint = mock(NotificationDeliveryRepository.SourceIdentity.class);
        when(hint.getSourceType()).thenReturn(NotificationSourceType.BOARDING_SESSION);
        when(hint.getBoardingSessionId()).thenReturn(fixture.sessionId());
        when(hint.getEventId()).thenReturn(Long.MAX_VALUE);
        doReturn(Optional.of(hint)).when(deliveries).findSourceIdentity(fixture.deliveryId());
        clearInvocations(devices, handler);
        assertThat(attempts.beginAttempt(fixture.deliveryId())).isEmpty();
        assertThat(row(fixture).getAttemptCount()).isZero();
        verifyNoInteractions(devices, handler);
    }

    @Test
    @DisplayName("READY가 없어도 Session 만료 backlog는 Alarm 만료의 독립 예산을 점유하지 않는다")
    void independent_expiry_budgets_without_ready_sessions() {
        var first = fixture(false);
        var second = fixture(false);
        long alarm = alarmDelivery();
        time.set(NOW.plusSeconds(60));
        var worker = worker(attempts, provider, 1);
        worker.dispatchPending();
        assertThat(deliveries.findById(alarm).orElseThrow().getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(row(first).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(row(second).getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        worker.dispatchPending();
        assertThat(row(second).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(row(first).getAttemptCount()).isZero();
        verifyNoInteractions(provider);
    }

    private long alarmDelivery() {
        return tx().execute(status -> {
            var user = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
            entityManager.persist(user);
            var device = new Device(user, UUID.randomUUID().toString(), "a".repeat(64), DevicePlatform.IOS,
                    "alarm-fid", 0, UTC_NOW);
            entityManager.persist(device);
            var evidence = new TransitEvent(TransitEventType.ARRIVED, "vehicle", UUID.randomUUID(), NOW,
                    null, null, null, null, null, null, null);
            var alarm = new com.stopbell.alarm.entity.Alarm(user, new com.stopbell.alarm.entity.BusAlarmTarget(
                    TransitProvider.SEOUL_BUS, "route-id", "s3", 30, "7000", "목적지", null, null, null, null, null));
            entityManager.persist(alarm);
            var event = new NotificationEvent(alarm.getId(), user, 1, evidence, UTC_NOW, UTC_NOW);
            entityManager.persist(event);
            var delivery = new NotificationDelivery(event, device, 0, UTC_NOW.plusSeconds(60), UTC_NOW);
            entityManager.persist(delivery);
            return delivery.getId();
        });
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException exception) { throw new AssertionError(exception); }
    }

    private record Fixture(long sessionId, long ownerId, long eventId, long deliveryId, long deviceId) { }

    private Fixture fixture(boolean ready) {
        var fixture = tx().execute(status -> {
            var user = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
            entityManager.persist(user);
            var session = session(user);
            entityManager.persist(session);
            var device = new Device(user, UUID.randomUUID().toString(), "a".repeat(64), DevicePlatform.IOS,
                    "fixture-fid-" + UUID.randomUUID(), 0, UTC_NOW);
            entityManager.persist(device);
            var event = NotificationEvent.forBoardingSession(session.getId(), user, BoardingNotificationEventType.PREPARE_ALIGHT,
                    session.getCheckpoint(), UTC_NOW, UTC_NOW, UTC_NOW.plusSeconds(30));
            entityManager.persist(event);
            var delivery = new NotificationDelivery(event, device, 0, event.getExpiresAt(), UTC_NOW);
            entityManager.persist(delivery);
            entityManager.flush();
            return new Fixture(session.getId(), user.getId(), event.getId(), delivery.getId(), device.getId());
        });
        if (ready) {
            runtime.verified(snapshot(fixture));
        }
        return fixture;
    }

    private Long completeDestination(Fixture fixture) {
        return tx().execute(status -> {
            var current = sessions.findForUpdate(fixture.sessionId()).orElseThrow();
            var expected = BoardingTrackingSnapshot.from(current);
            var target = current.getTraversal().occurrences().get(current.getTraversal().targetIndex());
            var checkpoint = new BoardingCheckpoint(target.externalStopId(), target.order(), current.getTraversal().targetIndex(),
                    BoardingCheckpoint.Evidence.ID_ORDER, null, utc(), utc());
            current.track(current.getRevision(), checkpoint, BoardingStateReason.ARRIVAL_OBSERVED, utc());
            sessions.flush();
            Long eventId = notifications.recordDecision(current, expected, new com.stopbell.boarding.domain.BoardingTrackingDecision(
                    com.stopbell.boarding.domain.BoardingTrackingDecision.Outcome.ARRIVAL_OBSERVED, checkpoint,
                    BoardingStateReason.ARRIVAL_OBSERVED, 0, utc(), utc().plusSeconds(30), utc()));
            notifications.terminatePending(current);
            return eventId;
        });
    }

    private BoardingTrackingSnapshot snapshot(Fixture fixture) {
        return tx().execute(status -> BoardingTrackingSnapshot.from(sessions.findForUpdate(fixture.sessionId()).orElseThrow()));
    }

    private BoardingTrackingSnapshot updateCheckpoint(Fixture fixture, int index) {
        return tx().execute(status -> {
            var session = sessions.findForUpdate(fixture.sessionId()).orElseThrow();
            var item = session.getTraversal().occurrences().get(index);
            session.track(session.getRevision(), new BoardingCheckpoint(item.externalStopId(), item.order(), index,
                    BoardingCheckpoint.Evidence.ID_ORDER, null, utc(), utc()), null, utc());
            sessions.flush();
            return BoardingTrackingSnapshot.from(session);
        });
    }

    private void terminate(Fixture fixture, GateFailure reason) {
        tx().executeWithoutResult(status -> {
            var session = sessions.findForUpdate(fixture.sessionId()).orElseThrow();
            runtime.committed(session.getId(), session.getRevision(), false);
            switch (reason) {
                case CANCELLED -> session.cancel(session.getRevision(), utc());
                case SUSPENDED -> session.suspend(session.getRevision(), BoardingStateReason.TEMPORARY_PROVIDER_FAILURE, utc());
                case COMPLETED -> {
                    var item = session.getTraversal().occurrences().get(2);
                    session.track(session.getRevision(), new BoardingCheckpoint(item.externalStopId(), item.order(), 2,
                            BoardingCheckpoint.Evidence.ID_ORDER, null, utc().plusSeconds(1), utc()),
                            BoardingStateReason.ARRIVAL_OBSERVED, utc().plusSeconds(1));
                }
                case EXPIRED -> { time.set(NOW.plusSeconds(14400)); session.expire(utc()); }
                default -> throw new IllegalArgumentException();
            }
            notifications.terminatePending(session);
        });
    }

    private long otherUser() {
        return tx().execute(status -> {
            var user = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
            entityManager.persist(user);
            return user.getId();
        });
    }

    private NotificationDelivery row(Fixture fixture) { return deliveries.findById(fixture.deliveryId()).orElseThrow(); }
    private LocalDateTime utc() { return LocalDateTime.ofInstant(time.get(), ZoneOffset.UTC); }
    private TransactionTemplate tx() { return new TransactionTemplate(manager); }
    private NotificationSendResult result(NotificationProviderResult kind) {
        return new NotificationSendResult(kind, kind == NotificationProviderResult.INVALID_TARGET ? "UNREGISTERED" : "UNAVAILABLE",
                Duration.ofSeconds(60), time.get(), 1, true);
    }
    private NotificationDispatchAttemptService service(PlatformTransactionManager testedManager, BoardingTrackingRuntime testedRuntime) {
        return new NotificationDispatchAttemptService(deliveries, new NotificationRecipientPreflight(devices), handler,
                testedManager, clock, sessions, testedRuntime);
    }
    private NotificationDispatchWorker worker(NotificationDispatchAttemptService service, FcmProviderClient fcm, int batch) {
        return new NotificationDispatchWorker(new NotificationDispatchProperties(true, batch, Duration.ofSeconds(1)),
                deliveries, service, fcm, clock, runtime);
    }
    private PlatformTransactionManager failingCommit(boolean committed) {
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                if (committed) { manager.commit(status); } else { manager.rollback(status); }
                throw new TransactionSystemException("synthetic uncertain commit");
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
    }
}
