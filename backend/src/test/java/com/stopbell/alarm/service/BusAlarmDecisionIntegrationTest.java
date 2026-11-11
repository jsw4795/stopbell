package com.stopbell.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stopbell.alarm.entity.AdjacentStopSnapshot;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.notification.entity.Device;
import com.stopbell.notification.entity.DevicePlatform;
import com.stopbell.notification.entity.NotificationDeliveryStatus;
import com.stopbell.notification.entity.NotificationEvent;
import com.stopbell.notification.entity.NotificationDelivery;
import com.stopbell.notification.service.NotificationDecisionTtlPolicy;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.notification.repository.NotificationEventRepository;
import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL transaction/constraint tests; no FCM or Transit HTTP I/O. */
@Tag("db")
@Tag("isolated-db")
@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BusAlarmDecisionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00.123456Z");
    private static final LocalDateTime UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final UUID CYCLE = UUID.fromString("a1234567-1234-4123-8123-123456789abc");
    private static SharedMySqlExtension.Database mysql;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        mysql = SharedMySqlExtension.database(registry, "bus_alarm_decision");
    }

    @Autowired private BusAlarmLifecycleService decisions;
    @Autowired private BusAlarmEvaluator evaluator;
    @Autowired private AlarmService alarmService;
    @Autowired private NotificationDecisionTtlPolicy ttlPolicy;
    @Autowired private AlarmRepository alarms;
    @Autowired private DeviceRepository devices;
    @MockitoSpyBean private NotificationEventRepository events;
    @MockitoSpyBean private NotificationDeliveryRepository deliveries;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean(name = "transitMetadataClock") private Clock clock;
    private TransactionTemplate tx;

    @BeforeEach
    void prepare() {
        tx = new TransactionTemplate(transactionManager);
        org.mockito.Mockito.when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void cleanup() {
        reset(events, deliveries);
        jdbc.execute("drop trigger if exists task708_delivery_failure");
        deliveries.deleteAll();
        events.deleteAll();
        alarms.deleteAll();
        devices.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("lifecycle·Event·여러 eligible Delivery를 함께 commit하고 duplicate는 늦은 Device와 기존 row를 변경하지 않는다")
    void commits_fan_out_and_preserves_duplicate() {
        Alarm alarm = alarm(true);
        Device first = device(alarm.getUser());
        Device second = device(alarm.getUser());
        Device disabled = device(alarm.getUser());
        tx.executeWithoutResult(status -> devices.findByInstallationIdForUpdate(disabled.getInstallationId()).orElseThrow().disable(1));
        device(user());
        TransitEvent event = event(TransitEventType.ARRIVED, CYCLE, NOW);
        var result = apply(alarm, AlarmStatus.ACTIVE, event);
        assertThat(result.outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.COMMITTED);
        Alarm current = alarms.findById(alarm.getId()).orElseThrow();
        assertThat(current.getStatus()).isEqualTo(AlarmStatus.FOLLOW_UP);
        assertThat(current.getFollowUpTrackingCycleId()).isEqualTo(CYCLE);
        var saved = events.findAll().getFirst();
        var recipients = deliveries.findAllByNotificationEventIdOrderByIdAsc(saved.getId());
        assertThat(recipients).extracting(row -> row.getDevice().getId()).containsExactly(first.getId(), second.getId());
        assertThat(recipients).allSatisfy(row -> {
            assertThat(row.getRecipientOwnershipGeneration()).isZero();
            assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
            assertThat(row.getAttemptCount()).isZero();
            assertThat(row.getExpiresAt()).isEqualTo(UTC.plusSeconds(60));
            assertThat(row.getNextAttemptAt()).isEqualTo(UTC);
        });
        var beforeEvent = jdbc.queryForList("select * from notification_events");
        var beforeDeliveries = jdbc.queryForList("select * from notification_deliveries order by id");
        var beforeAlarm = jdbc.queryForMap("select * from alarms where id=?", alarm.getId());
        device(alarm.getUser());
        org.mockito.Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        var duplicate = apply(alarm, AlarmStatus.ACTIVE, event);
        assertThat(duplicate.outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.DUPLICATE);
        assertThat(duplicate.permitsMemory(key(alarm), AlarmStatus.ACTIVE, event)).isTrue();
        assertThat(jdbc.queryForList("select * from notification_events")).isEqualTo(beforeEvent);
        assertThat(jdbc.queryForList("select * from notification_deliveries order by id")).isEqualTo(beforeDeliveries);
        assertThat(jdbc.queryForMap("select * from alarms where id=?", alarm.getId())).isEqualTo(beforeAlarm);
    }

    @Test
    @DisplayName("같은 네 identity는 하나로 유지하고 type·cycle·새 generation은 별도 Event로 저장한다")
    void distinguishes_all_identity_components() {
        Alarm alarm = alarm(false);
        TransitEvent before = event(TransitEventType.ONE_STOP_BEFORE, CYCLE, NOW);
        assertThat(apply(alarm, AlarmStatus.ACTIVE, before).outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.COMMITTED);
        assertThat(apply(alarm, AlarmStatus.ACTIVE, before).outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.DUPLICATE);
        apply(alarm, AlarmStatus.ACTIVE, event(TransitEventType.PASSED, CYCLE, NOW));
        apply(alarm, AlarmStatus.ACTIVE, event(TransitEventType.ONE_STOP_BEFORE, UUID.randomUUID(), NOW));
        tx.executeWithoutResult(status -> {
            Alarm current = alarms.findByIdForUpdate(alarm.getId()).orElseThrow();
            current.activate(); // ACTIVE -> ACTIVE preserves generation.
            assertThat(current.getActivationGeneration()).isEqualTo(alarm.getActivationGeneration());
            current.deactivate();
            current.activate();
        });
        assertThat(apply(alarm, AlarmStatus.ACTIVE, before).outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.STALE);
        Alarm reactivated = alarms.findById(alarm.getId()).orElseThrow();
        apply(reactivated, AlarmStatus.ACTIVE, before);
        assertThat(events.count()).isEqualTo(4);
        assertThat(deliveries.count()).isZero();
    }

    @Test
    @DisplayName("ACTIVE 재시작 baseline은 동일 predecessor의 before Event를 중복 저장하지 않는다")
    void characterizes_active_restart_before_duplicate() {
        Alarm alarm = alarm(false);
        device(alarm.getUser());
        var observation = new com.stopbell.transit.domain.TransitObservation(
                TransitProvider.SEOUL_BUS, "route", "vehicle-1", "before", 2,
                null, null, null, null, null, NOW, null, com.stopbell.transit.domain.ArrivalEvidence.MOVING);
        var first = evaluator.evaluate(alarm, BusAlarmEvaluationState.initial(), List.of(observation), NOW);
        assertThat(first.eventCandidates()).singleElement()
                .extracting(TransitEvent::type).isEqualTo(TransitEventType.ONE_STOP_BEFORE);
        assertThat(decisions.applyIfCurrent(key(alarm), AlarmStatus.ACTIVE, first, NOW).outcome())
                .isEqualTo(BusAlarmDecisionResult.Outcome.COMMITTED);
        assertThat(alarms.findById(alarm.getId()).orElseThrow().isInitialBaselinePending()).isFalse();
        assertThat(evaluator.evaluate(alarm, first.nextState(), List.of(observation), NOW).eventCandidates()).isEmpty();

        Alarm reloaded = alarms.findById(alarm.getId()).orElseThrow();
        var afterMemoryLoss = evaluator.evaluate(reloaded, BusAlarmEvaluationState.initial(), List.of(observation), NOW);
        assertThat(afterMemoryLoss.eventCandidates()).isEmpty();
        assertThat(decisions.applyIfCurrent(key(reloaded), AlarmStatus.ACTIVE, afterMemoryLoss, NOW).outcome())
                .isEqualTo(BusAlarmDecisionResult.Outcome.COMMITTED);
        assertThat(evaluator.evaluate(reloaded, afterMemoryLoss.nextState(), List.of(observation), NOW)
                .eventCandidates()).isEmpty();
        assertThat(events.count()).isOne();
        assertThat(deliveries.count()).isOne();
        var later = new com.stopbell.transit.domain.TransitObservation(
                TransitProvider.SEOUL_BUS, "route", "vehicle-1", "beyond", 4,
                null, null, null, null, null, NOW.plusSeconds(1), null, com.stopbell.transit.domain.ArrivalEvidence.MOVING);
        assertThat(evaluator.evaluate(reloaded, afterMemoryLoss.nextState(), List.of(later), NOW.plusSeconds(1))
                .eventCandidates()).singleElement().extracting(TransitEvent::type).isEqualTo(TransitEventType.PASSED);
    }

    @Test
    @DisplayName("첫 평가 경합과 이전 generation 결과는 새 baseline을 덮어쓰지 않는다")
    void baseline_is_guarded_by_generation_and_current_row() throws Exception {
        Alarm alarm = alarm(false);
        device(alarm.getUser());
        var observation = new com.stopbell.transit.domain.TransitObservation(
                TransitProvider.SEOUL_BUS, "route", "vehicle-1", "before", 2,
                null, null, null, null, null, NOW, null, com.stopbell.transit.domain.ArrivalEvidence.MOVING);
        var first = evaluator.evaluate(alarm, BusAlarmEvaluationState.initial(), List.of(observation), NOW);
        var competing = evaluator.evaluate(alarm, BusAlarmEvaluationState.initial(), List.of(observation), NOW);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return decisions.applyIfCurrent(key(alarm), AlarmStatus.ACTIVE, first, NOW); });
            var b = pool.submit(() -> { start.await(); return decisions.applyIfCurrent(key(alarm), AlarmStatus.ACTIVE, competing, NOW); });
            start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS).outcome(), b.get(15, TimeUnit.SECONDS).outcome()))
                    .containsExactlyInAnyOrder(BusAlarmDecisionResult.Outcome.COMMITTED, BusAlarmDecisionResult.Outcome.STALE);
        }
        assertThat(events.count()).isOne();
        assertThat(deliveries.count()).isOne();
        tx.executeWithoutResult(status -> {
            Alarm current = alarms.findByIdForUpdate(alarm.getId()).orElseThrow();
            current.activate();
            assertThat(current.isInitialBaselinePending()).isFalse();
            current.deactivate();
            current.activate();
            assertThat(current.isInitialBaselinePending()).isTrue();
        });
        assertThat(decisions.applyIfCurrent(key(alarm), AlarmStatus.ACTIVE, competing, NOW).outcome())
                .isEqualTo(BusAlarmDecisionResult.Outcome.STALE);
        assertThat(alarms.findById(alarm.getId()).orElseThrow().isInitialBaselinePending()).isTrue();
    }

    @Test
    @DisplayName("실제 Backend JVM SIGTERM 후 재시작은 predecessor Event와 Delivery를 중복 생성하지 않는다")
    void process_restart_keeps_baseline_and_pending_delivery() throws Exception {
        Alarm alarm = alarm(false);
        device(alarm.getUser());
        java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("task811-restart-");
        try {
            runProbe(alarm.getId(), temp, "first", "COMMITTED:1");
            // Synthetic previously retried row, due now; restart must retain its consumed budget and expiry.
            jdbc.update("update notification_deliveries set attempt_count=1, last_attempt_at=UTC_TIMESTAMP(6), "
                    + "last_attempt_registration_revision=0, last_provider_result='RETRYABLE', "
                    + "last_failure_code='UNAVAILABLE', next_attempt_at=UTC_TIMESTAMP(6)");
            var before = jdbc.queryForMap("select status, attempt_count, next_attempt_at, expires_at from notification_deliveries");
            runProbe(alarm.getId(), temp, "second", "COMMITTED:0");
            assertThat(events.count()).isOne();
            assertThat(deliveries.count()).isOne();
            assertThat(jdbc.queryForMap("select status, attempt_count, next_attempt_at, expires_at from notification_deliveries"))
                    .isEqualTo(before);
            runProbe(alarm.getId(), temp, "dispatch", "DISPATCHED");
            var after = jdbc.queryForMap("select status, attempt_count, expires_at from notification_deliveries");
            assertThat(after.get("status")).isEqualTo("ACCEPTED");
            assertThat(((Number) after.get("attempt_count")).intValue()).isEqualTo(2);
            assertThat(after.get("expires_at")).isEqualTo(before.get("expires_at"));
        } finally {
            try (var files = java.nio.file.Files.list(temp)) {
                for (var file : files.toList()) java.nio.file.Files.deleteIfExists(file);
            }
            java.nio.file.Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("별도 Backend 프로세스는 FOLLOW_UP의 기존 cycle UUID를 복구한다")
    void process_restart_restores_follow_up() throws Exception {
        Instant now = Instant.now();
        org.mockito.Mockito.when(clock.instant()).thenReturn(now);
        Alarm alarm = alarm(true);
        TransitEvent arrival = event(TransitEventType.ARRIVED, CYCLE, now);
        assertThat(decisions.applyIfCurrent(key(alarm), AlarmStatus.ACTIVE,
                new BusAlarmEvaluationResult(List.of(arrival), BusAlarmEvaluationState.initial(), false, true), now).outcome())
                .isEqualTo(BusAlarmDecisionResult.Outcome.COMMITTED);
        java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("task811-followup-");
        try {
            runProbe(alarm.getId(), temp, "followup", "COMMITTED:1");
            assertThat(alarms.findById(alarm.getId()).orElseThrow().getStatus()).isEqualTo(AlarmStatus.INACTIVE);
            assertThat(events.findAll()).allSatisfy(row -> assertThat(row.getTrackingCycleId()).isEqualTo(CYCLE));
        } finally {
            try (var files = java.nio.file.Files.list(temp)) {
                for (var file : files.toList()) java.nio.file.Files.deleteIfExists(file);
            }
            java.nio.file.Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("SIGTERM 시 미완료 transaction은 rollback되고 재시작 뒤 최초 baseline이 평가된다")
    void process_shutdown_rolls_back_inflight_baseline() throws Exception {
        Alarm alarm = alarm(false);
        java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("task811-inflight-");
        try {
            runProbe(alarm.getId(), temp, "inflight", "IN_FLIGHT");
            assertThat(alarms.findById(alarm.getId()).orElseThrow().isInitialBaselinePending()).isTrue();
            assertThat(events.count()).isZero();
            runProbe(alarm.getId(), temp, "first", "COMMITTED:1");
            assertThat(alarms.findById(alarm.getId()).orElseThrow().isInitialBaselinePending()).isFalse();
            assertThat(events.count()).isOne();
        } finally {
            try (var files = java.nio.file.Files.list(temp)) {
                for (var file : files.toList()) java.nio.file.Files.deleteIfExists(file);
            }
            java.nio.file.Files.deleteIfExists(temp);
        }
    }

    private void runProbe(Long alarmId, java.nio.file.Path temp, String name, String expected) throws Exception {
        var marker = temp.resolve(name + ".ready");
        var log = temp.resolve(name + ".log");
        Process process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("task811.test.classpath"), RestartProcessProbe.class.getName(),
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), alarmId.toString(), marker.toString(), name)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!java.nio.file.Files.exists(marker) && process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            boolean exists = java.nio.file.Files.exists(marker);
            String actual = exists ? java.nio.file.Files.readString(marker) : "<missing>";
            String safeActual = actual.matches("[A-Z0-9_:]*") ? actual : "<redacted>";
            String status = process.isAlive() ? "running" : "exited:" + process.exitValue();
            String diagnostics = "mode=" + name + " markerExists=" + exists + " expected=" + expected
                    + " actual=" + safeActual + " child=" + status;
            assertThat(exists).as(diagnostics).isTrue();
            assertThat(safeActual).as(diagnostics).isEqualTo(expected);
            assertThat(process.isAlive()).as(diagnostics).isTrue();
        } finally {
            process.destroy(); // SIGTERM on the supported Unix CI and development environments.
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError("Child did not terminate gracefully");
            }
        }
        assertThat(java.nio.file.Files.readString(log).contains("Shutdown completed"))
                .as("mode=%s child shutdown completed", name).isTrue();
    }

    @Test
    @DisplayName("0개 recipient도 Event를 보존하고 이미 만료된 유효 결정은 EXPIRED Delivery와 lifecycle을 commit한다")
    void keeps_zero_recipients_and_expired_decisions() {
        Alarm empty = alarm(false);
        assertThat(apply(empty, AlarmStatus.ACTIVE, event(TransitEventType.ARRIVED, CYCLE, NOW)).status()).isEqualTo(AlarmStatus.INACTIVE);
        assertThat(events.count()).isOne();
        assertThat(deliveries.count()).isZero();
        Alarm expired = alarm(true);
        device(expired.getUser());
        TransitEvent event = event(TransitEventType.ARRIVED, CYCLE, NOW.minusSeconds(61));
        apply(expired, AlarmStatus.ACTIVE, event);
        var delivery = deliveries.findAll().getFirst();
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(delivery.getAttemptCount()).isZero();
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getExpiresAt()).isEqualTo(UTC.minusSeconds(1));
        assertThat(alarms.findById(expired.getId()).orElseThrow().getStatus()).isEqualTo(AlarmStatus.FOLLOW_UP);
        org.mockito.Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        assertThat(apply(expired, AlarmStatus.ACTIVE, event).outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.DUPLICATE);
        assertThat(deliveries.findById(delivery.getId()).orElseThrow().getExpiresAt()).isEqualTo(UTC.minusSeconds(1));
    }

    @Test
    @DisplayName("Delivery CHECK·FK 실패는 FOLLOW_UP UUID·lifecycle·Event·모든 Delivery를 rollback하고 duplicate로 숨기지 않는다")
    void rolls_back_everything_on_delivery_failure() {
        Alarm alarm = alarm(true);
        device(alarm.getUser());
        Device second = device(alarm.getUser());
        TransitEvent event = event(TransitEventType.ARRIVED, CYCLE, NOW);
        for (String failure : List.of("set NEW.attempt_count = -1", "set NEW.device_id = 9223372036854775807")) {
            jdbc.execute("create trigger task708_delivery_failure before insert on notification_deliveries for each row begin if NEW.device_id = "
                    + second.getId() + " then " + failure + "; end if; end");
            try {
                assertThatThrownBy(() -> apply(alarm, AlarmStatus.ACTIVE, event)).isInstanceOf(DataIntegrityViolationException.class);
                Alarm unchanged = alarms.findById(alarm.getId()).orElseThrow();
                assertThat(unchanged.getStatus()).isEqualTo(AlarmStatus.ACTIVE);
                assertThat(unchanged.getFollowUpTrackingCycleId()).isNull();
                assertThat(events.count()).isZero();
                assertThat(deliveries.count()).isZero();
            } finally {
                jdbc.execute("drop trigger task708_delivery_failure");
            }
        }
        assertThat(apply(alarm, AlarmStatus.ACTIVE, event).outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.COMMITTED);
        assertThat(deliveries.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Delivery UNIQUE 실패도 정상 Event duplicate로 숨기지 않고 lifecycle·Event·Delivery를 함께 rollback한다")
    void rejects_delivery_unique_as_event_duplicate() {
        Alarm alarm = alarm(true);
        device(alarm.getUser());
        doAnswer(invocation -> {
            NotificationDelivery row = invocation.getArgument(0);
            String sql = "insert into notification_deliveries (notification_event_id, device_id, recipient_ownership_generation, "
                    + "status, next_attempt_at, expires_at, created_at, updated_at) values (?, ?, ?, 'PENDING', ?, ?, ?, ?)";
            Object[] values = { row.getNotificationEvent().getId(), row.getDevice().getId(),
                    row.getRecipientOwnershipGeneration(), UTC, row.getExpiresAt(), UTC, UTC };
            jdbc.update(sql, values);
            jdbc.update(sql, values); // Actual MySQL event × Device UNIQUE failure inside the decision transaction.
            return row;
        }).when(deliveries).save(any(NotificationDelivery.class));
        assertThatThrownBy(() -> apply(alarm, AlarmStatus.ACTIVE, event(TransitEventType.ARRIVED, CYCLE, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_notification_deliveries_event_device");
        assertThat(alarms.findById(alarm.getId()).orElseThrow().getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(events.count()).isZero();
        assertThat(deliveries.count()).isZero();
    }

    @Test
    @DisplayName("실제 commit 뒤 응답 유실은 새 transaction에서 같은 identity를 duplicate로 확인하고 최초 시각·Delivery를 보존한다")
    void reconciles_lost_commit_response() {
        Alarm alarm = alarm(true);
        device(alarm.getUser());
        java.util.concurrent.atomic.AtomicBoolean loseOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        PlatformTransactionManager ambiguous = new PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return transactionManager.getTransaction(definition);
            }
            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) {
                transactionManager.commit(status); // Real commit completed; only its acknowledgement is lost.
                if (loseOnce.getAndSet(false)) {
                    throw new org.springframework.transaction.TransactionSystemException("commit acknowledgement lost");
                }
            }
            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) {
                transactionManager.rollback(status);
            }
        };
        BusAlarmLifecycleService service = new BusAlarmLifecycleService(alarms, events, deliveries, devices, ttlPolicy, ambiguous, clock);
        TransitEvent event = event(TransitEventType.ARRIVED, CYCLE, NOW.minusSeconds(2));
        var result = service.applyIfCurrent(key(alarm), AlarmStatus.ACTIVE,
                new BusAlarmEvaluationResult(List.of(event), BusAlarmEvaluationState.initial(), false, true), NOW);
        assertThat(result.outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.DUPLICATE);
        assertThat(result.followUpTrackingCycleId()).isEqualTo(CYCLE);
        assertThat(events.count()).isOne();
        assertThat(events.findAll().getFirst().getObservedAt()).isEqualTo(UTC.minusSeconds(2));
        assertThat(events.findAll().getFirst().getEventDetectedAt()).isEqualTo(UTC);
        assertThat(deliveries.count()).isOne();
        assertThat(alarms.findById(alarm.getId()).orElseThrow().isInitialBaselinePending()).isFalse();
        assertThat(deliveries.findAll().getFirst().getExpiresAt()).isEqualTo(UTC.plusSeconds(58));
    }

    @Test
    @DisplayName("commit 전 rollback은 부분 결과를 남기지 않고 원래 identity를 재검증해 한 번만 저장한다")
    void retries_original_candidate_after_pre_commit_rollback() {
        Alarm alarm = alarm(true);
        device(alarm.getUser());
        var commits = new java.util.concurrent.atomic.AtomicInteger();
        PlatformTransactionManager failingOnce = new PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return transactionManager.getTransaction(definition);
            }
            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) {
                if (commits.incrementAndGet() == 1) {
                    transactionManager.rollback(status);
                    assertThat(alarms.findById(alarm.getId()).orElseThrow().getStatus()).isEqualTo(AlarmStatus.ACTIVE);
                    assertThat(alarms.findById(alarm.getId()).orElseThrow().isInitialBaselinePending()).isTrue();
                    assertThat(alarms.findById(alarm.getId()).orElseThrow().getFollowUpTrackingCycleId()).isNull();
                    assertThat(events.count()).isZero();
                    assertThat(deliveries.count()).isZero();
                    throw new org.springframework.transaction.TransactionSystemException("commit rolled back");
                }
                transactionManager.commit(status);
            }
            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) {
                transactionManager.rollback(status);
            }
        };
        BusAlarmLifecycleService service = new BusAlarmLifecycleService(alarms, events, deliveries, devices, ttlPolicy, failingOnce, clock);
        TransitEvent event = event(TransitEventType.ARRIVED, CYCLE, NOW);
        var result = service.applyIfCurrent(key(alarm), AlarmStatus.ACTIVE,
                new BusAlarmEvaluationResult(List.of(event), BusAlarmEvaluationState.initial(), false, true), NOW);

        assertThat(commits.get()).isEqualTo(2);
        assertThat(result.outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.COMMITTED);
        assertThat(alarms.findById(alarm.getId()).orElseThrow().isInitialBaselinePending()).isFalse();
        assertThat(result.followUpTrackingCycleId()).isEqualTo(CYCLE);
        assertThat(events.count()).isOne();
        var saved = events.findAll().getFirst();
        assertThat(saved.getActivationGeneration()).isEqualTo(alarm.getActivationGeneration());
        assertThat(saved.getTrackingCycleId()).isEqualTo(CYCLE);
        assertThat(saved.getEventType()).isEqualTo(TransitEventType.ARRIVED);
        assertThat(deliveries.count()).isOne();
    }

    @Test
    @DisplayName("실제 Event UNIQUE 충돌 전체 rollback 뒤 새 transaction의 exact 조회로만 duplicate를 분류한다")
    void resolves_real_unique_conflict_after_rollback() {
        Alarm alarm = alarm(true);
        device(alarm.getUser());
        TransitEvent event = event(TransitEventType.ARRIVED, CYCLE, NOW);
        NotificationEvent existing = events.saveAndFlush(new NotificationEvent(alarm.getId(), alarm.getUser(), 1, event, UTC, UTC));
        // Force a missed pre-read; the INSERT still uses real MySQL UNIQUE and transaction rollback.
        doReturn(Optional.empty(), Optional.of(existing)).when(events)
                .findByAlarmIdAndActivationGenerationAndTrackingCycleIdAndEventType(alarm.getId(), 1, CYCLE.toString(), event.type());
        var duplicate = apply(alarm, AlarmStatus.ACTIVE, event);
        assertThat(duplicate.outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.DUPLICATE);
        assertThat(alarms.findById(alarm.getId()).orElseThrow().getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(alarms.findById(alarm.getId()).orElseThrow().getFollowUpTrackingCycleId()).isNull();
        assertThat(events.count()).isOne();
        assertThat(deliveries.count()).isZero();
        doReturn(Optional.empty()).when(events)
                .findByAlarmIdAndActivationGenerationAndTrackingCycleIdAndEventType(alarm.getId(), 1, CYCLE.toString(), event.type());
        assertThatThrownBy(() -> apply(alarm, AlarmStatus.ACTIVE, event)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("동시 동일 candidate는 한 결정과 duplicate로 직렬화되고 최초 recipient 하나만 생성한다")
    void serializes_concurrent_same_candidate() throws Exception {
        Alarm alarm = alarm(true);
        device(alarm.getUser());
        TransitEvent event = event(TransitEventType.ARRIVED, CYCLE, NOW);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return apply(alarm, AlarmStatus.ACTIVE, event); });
            var second = pool.submit(() -> { start.await(); return apply(alarm, AlarmStatus.ACTIVE, event); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS).outcome(), second.get(15, TimeUnit.SECONDS).outcome()))
                    .containsExactlyInAnyOrder(BusAlarmDecisionResult.Outcome.COMMITTED, BusAlarmDecisionResult.Outcome.DUPLICATE);
        }
        assertThat(events.count()).isOne();
        assertThat(deliveries.count()).isOne();
    }

    @Test
    @DisplayName("current Device 잠금 조회는 이전 snapshot 뒤 ownership 이전과 조율하고 old owner fan-out에서 제외한다")
    void current_read_coordinates_ownership_transfer() throws Exception {
        Alarm alarm = alarm(true);
        Device device = device(alarm.getUser());
        User nextOwner = user();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch transfer = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var mutation = pool.submit(() -> tx.executeWithoutResult(status -> {
                Device current = devices.findByInstallationIdForUpdate(device.getInstallationId()).orElseThrow();
                locked.countDown();
                await(transfer);
                current.transferOwnership(nextOwner, UUID.randomUUID().toString(), 1, UTC);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var decision = pool.submit(() -> apply(alarm, AlarmStatus.ACTIVE, event(TransitEventType.ARRIVED, CYCLE, NOW)));
            transfer.countDown();
            mutation.get(15, TimeUnit.SECONDS);
            assertThat(decision.get(15, TimeUnit.SECONDS).outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.COMMITTED);
        }
        assertThat(events.count()).isOne();
        assertThat(deliveries.count()).isZero();
        // A -> B -> A is a new recipient ownership generation.
        tx.executeWithoutResult(status -> devices.findByInstallationIdForUpdate(device.getInstallationId()).orElseThrow()
                .transferOwnership(alarm.getUser(), UUID.randomUUID().toString(), 2, UTC));
        assertThat(apply(alarm, AlarmStatus.ACTIVE, event(TransitEventType.ARRIVED, CYCLE, NOW)).outcome())
                .isEqualTo(BusAlarmDecisionResult.Outcome.DUPLICATE);
        assertThat(deliveries.count()).isZero();
    }

    @Test
    @DisplayName("ARRIVED 원래 cycle로 after를 완료하고 중복 after·reactivation·삭제 및 만료는 stale side effect를 차단한다")
    void preserves_follow_up_and_rejects_stale_lifecycle() {
        Alarm alarm = alarm(true);
        TransitEvent arrival = event(TransitEventType.ARRIVED, CYCLE, NOW);
        apply(alarm, AlarmStatus.ACTIVE, arrival);
        assertThat(apply(alarm, AlarmStatus.ACTIVE, event(TransitEventType.ARRIVED, UUID.randomUUID(), NOW)).outcome())
                .isEqualTo(BusAlarmDecisionResult.Outcome.STALE);
        TransitEvent wrongCycle = event(TransitEventType.ONE_STOP_AFTER, UUID.randomUUID(), NOW);
        assertThat(apply(alarm, AlarmStatus.FOLLOW_UP, wrongCycle).outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.STALE);
        Alarm restarted = alarms.findById(alarm.getId()).orElseThrow();
        var restored = evaluator.evaluate(restarted, BusAlarmEvaluationState.initial(), List.of(
                new com.stopbell.transit.domain.TransitObservation(TransitProvider.SEOUL_BUS, "route", "vehicle-1", "after", 4,
                        null, null, null, null, null, NOW, null, com.stopbell.transit.domain.ArrivalEvidence.MOVING)), NOW);
        TransitEvent after = restored.eventCandidates().getFirst();
        assertThat(after.trackingCycleId()).isEqualTo(CYCLE);
        assertThat(restored.nextState().followUpVehicles().get("vehicle-1").trackingCycleId()).isEqualTo(CYCLE);
        var completed = decisions.applyIfCurrent(key(restarted), AlarmStatus.FOLLOW_UP, restored, NOW);
        assertThat(completed.status()).isEqualTo(AlarmStatus.INACTIVE);
        assertThat(alarms.findById(alarm.getId()).orElseThrow().getFollowUpTrackingCycleId()).isNull();
        var duplicate = apply(alarm, AlarmStatus.FOLLOW_UP, after);
        assertThat(duplicate.outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.DUPLICATE);
        assertThat(duplicate.permitsMemory(key(alarm), AlarmStatus.FOLLOW_UP, after)).isTrue();
        tx.executeWithoutResult(status -> alarms.findByIdForUpdate(alarm.getId()).orElseThrow().activate());
        assertThat(apply(alarm, AlarmStatus.FOLLOW_UP, after).outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.STALE);
        alarms.deleteById(alarm.getId());
        assertThat(apply(alarm, AlarmStatus.ACTIVE, arrival).outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.STALE);
        assertThat(events.count()).isEqualTo(2);

        Alarm expiring = alarm(true);
        apply(expiring, AlarmStatus.ACTIVE, arrival);
        Alarm superseded = alarm(true);
        apply(superseded, AlarmStatus.ACTIVE, arrival);
        tx.executeWithoutResult(status -> alarms.findByIdForUpdate(superseded.getId()).orElseThrow().activate());
        assertThat(apply(superseded, AlarmStatus.FOLLOW_UP, after).outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.STALE);
        assertThat(alarms.findById(superseded.getId()).orElseThrow().getFollowUpTrackingCycleId()).isNull();

        org.mockito.Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        var expiry = decisions.applyIfCurrent(key(expiring), AlarmStatus.FOLLOW_UP,
                new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), true), NOW.plusSeconds(300));
        assertThat(expiry.status()).isEqualTo(AlarmStatus.INACTIVE);
        assertThat(events.count()).isEqualTo(4); // No fabricated expiry Event.
    }

    @Test
    @DisplayName("Alarm 잠금을 먼저 얻은 deactivate·reactivate·delete는 경합하는 old candidate의 Event와 Delivery 생성을 차단한다")
    void coordinates_alarm_mutation_with_decision() throws Exception {
        for (String mutation : List.of("deactivate", "reactivate", "delete")) {
            Alarm alarm = alarm(true);
            device(alarm.getUser());
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var change = pool.submit(() -> tx.executeWithoutResult(status -> {
                    Alarm current = alarms.findByIdForUpdate(alarm.getId()).orElseThrow();
                    locked.countDown();
                    await(release);
                    if (mutation.equals("delete")) {
                        alarmService.delete(alarm.getUser().getId(), alarm.getId());
                    } else {
                        current.deactivate();
                        if (mutation.equals("reactivate")) current.activate();
                    }
                }));
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var decision = pool.submit(() -> apply(alarm, AlarmStatus.ACTIVE, event(TransitEventType.ARRIVED, CYCLE, NOW)));
                release.countDown();
                change.get(15, TimeUnit.SECONDS);
                assertThat(decision.get(15, TimeUnit.SECONDS).outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.STALE);
            }
        }
        assertThat(events.count()).isZero();
        assertThat(deliveries.count()).isZero();
    }

    @Test
    @DisplayName("같은 transaction의 과거 snapshot이 있어도 Event exact locking read는 최신 commit을 읽는다")
    void exact_read_ignores_old_repeatable_read_snapshot() throws Exception {
        Alarm alarm = alarm(false);
        TransitEvent event = event(TransitEventType.ONE_STOP_BEFORE, CYCLE, NOW);
        try (var pool = Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(status -> {
                assertThat(events.count()).isZero(); // Establish REPEATABLE READ snapshot.
                try {
                    pool.submit(() -> apply(alarm, AlarmStatus.ACTIVE, event)).get(15, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
                assertThat(events.findByAlarmIdAndActivationGenerationAndTrackingCycleIdAndEventType(
                        alarm.getId(), 1, CYCLE.toString(), event.type())).isPresent();
            });
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out coordinating test");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private User user() {
        return users.saveAndFlush(new User(AuthProvider.GOOGLE, UUID.randomUUID().toString()));
    }

    private Alarm alarm(boolean after) {
        User user = user();
        Alarm alarm = new Alarm(user, new BusAlarmTarget(TransitProvider.SEOUL_BUS, "route", "target", 3, "143", "목적지",
                null, null, null, new AdjacentStopSnapshot("before", 2), after ? new AdjacentStopSnapshot("after", 4) : null));
        alarm.activate();
        return alarms.saveAndFlush(alarm);
    }

    private Device device(User user) {
        return devices.saveAndFlush(new Device(user, UUID.randomUUID().toString(), "a".repeat(64), DevicePlatform.IOS,
                UUID.randomUUID().toString(), 0, UTC));
    }

    private static AlarmEvaluationKey key(Alarm alarm) {
        return new AlarmEvaluationKey(alarm.getId(), alarm.getActivationGeneration());
    }

    private BusAlarmDecisionResult apply(Alarm alarm, AlarmStatus status, TransitEvent event) {
        return decisions.applyIfCurrent(key(alarm), status,
                new BusAlarmEvaluationResult(List.of(event), BusAlarmEvaluationState.initial(), false), NOW);
    }

    private static TransitEvent event(TransitEventType type, UUID cycle, Instant observedAt) {
        String stop = switch (type) { case ONE_STOP_BEFORE -> "before"; case ARRIVED -> "target"; default -> "after"; };
        int order = switch (type) { case ONE_STOP_BEFORE -> 2; case ARRIVED -> 3; default -> 4; };
        return new TransitEvent(type, "vehicle-1", cycle, observedAt, stop, "실제 정류장", order, null, null, null,
                type == TransitEventType.PASSED ? 1 : null);
    }
}
