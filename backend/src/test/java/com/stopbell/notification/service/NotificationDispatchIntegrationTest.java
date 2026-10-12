package com.stopbell.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.service.AlarmService;
import com.stopbell.notification.client.FcmProviderClient;
import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.notification.entity.*;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "notification.dispatch.enabled=false"})
@ActiveProfiles("test")
@Testcontainers
class NotificationDispatchIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 0, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationDeliveryRepository deliveries;
    @Autowired private DeviceRepository devices;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private AlarmService alarms;

    private FixtureHandler handler;
    private FcmProviderClient provider;
    private NotificationDispatchAttemptService attempts;
    private NotificationDispatchWorker worker;

    @BeforeEach
    void set_up() {
        // Only this class's isolated Testcontainers database; no local DB is used.
        jdbc.update("delete from notification_deliveries");
        jdbc.update("delete from notification_events");
        handler = new FixtureHandler();
        provider = mock(FcmProviderClient.class);
        attempts = attempts(manager);
        worker = worker(attempts, 50);
        when(provider.send(any())).thenReturn(new NotificationSendResult(
                NotificationProviderResult.ACCEPTED, null, null, CLOCK.instant(), 1, true));
    }

    @Test
    @DisplayName("due 순서와 batch 제한을 유지하고 future 및 terminal을 제외하되 expired와 capped 후보는 숨기지 않는다")
    void bounded_due_candidates_preserve_local_termination() {
        Fixture first = fixture();
        Fixture second = fixture();
        Fixture expired = fixture();
        Fixture capped = fixture();
        Fixture future = fixture();
        Fixture terminal = fixture();
        jdbc.update("update notification_deliveries set next_attempt_at=? where id=?", NOW.minusSeconds(1), second.deliveryId());
        jdbc.update("update notification_deliveries set expires_at=?, next_attempt_at=? where id=?", NOW, NOW, expired.deliveryId());
        jdbc.update("update notification_deliveries set attempt_count=3, last_attempt_at=?, last_attempt_registration_revision=0 where id=?", NOW, capped.deliveryId());
        jdbc.update("update notification_deliveries set next_attempt_at=? where id=?", NOW.plusSeconds(1), future.deliveryId());
        terminate(terminal.deliveryId());
        assertThat(attempts.beginAttempt(future.deliveryId())).isEmpty();
        assertThat(attempts.beginAttempt(terminal.deliveryId())).isEmpty();
        assertThat(attempts.beginAttempt(Long.MAX_VALUE)).isEmpty();
        assertThat(deliveries.findDuePendingIds(NOW, PageRequest.of(0, 2)))
                .containsExactly(second.deliveryId(), first.deliveryId());
        worker(attempts, 2).dispatchPending();
        assertThat(handler.eligibilityIds).containsExactly(second.deliveryId(), first.deliveryId());
        worker.dispatchPending();
        assertThat(handler.eligibilityIds).containsExactly(second.deliveryId(), first.deliveryId(), expired.deliveryId(), capped.deliveryId());
        verify(provider, times(2)).send(any());
    }

    @Test
    @DisplayName("다른 owner 및 A→B→A generation과 disabled Device를 current locking read로 차단한다")
    void rejects_changed_ownership_generation_and_disabled() {
        Fixture changed = fixture();
        Fixture returned = fixture();
        Fixture disabled = fixture();
        tx().executeWithoutResult(status -> {
            User other = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
            entityManager.persist(other);
            devices.findByIdForUpdate(changed.deviceId()).orElseThrow()
                    .transferOwnership(other, "other-" + changed.deviceId(), 1, NOW);
            Device back = devices.findByIdForUpdate(returned.deviceId()).orElseThrow();
            back.transferOwnership(other, "other-" + returned.deviceId(), 1, NOW);
            back.transferOwnership(entityManager.find(User.class, returned.userId()), "returned-fid", 2, NOW);
            devices.findByIdForUpdate(disabled.deviceId()).orElseThrow().disable(1);
        });
        worker.dispatchPending();
        assertThat(handler.rejections).containsExactly(NotificationRecipientPreflight.Rejection.OWNER_CHANGED,
                NotificationRecipientPreflight.Rejection.GENERATION_CHANGED, NotificationRecipientPreflight.Rejection.DISABLED);
        verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("select sum(attempt_count) from notification_deliveries", Integer.class)).isZero();
    }

    @Test
    @DisplayName("동일 owner와 generation의 FID rotation은 현재 revision을 사용하고 늦은 Device에는 Delivery를 추가하지 않는다")
    void uses_current_rotation_without_new_fanout_or_alarm_status_gate() {
        Fixture fixture = fixture(); // Alarm is INACTIVE, as a normal ARRIVED can be.
        tx().executeWithoutResult(status -> {
            User user = entityManager.find(User.class, fixture.userId());
            devices.findByIdForUpdate(fixture.deviceId()).orElseThrow().register("rotated-fid", 2, NOW);
            entityManager.persist(device(user));
        });
        when(provider.send(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            assertThat(jdbc.queryForObject("select attempt_count from notification_deliveries where id=?", Integer.class, fixture.deliveryId())).isOne();
            // New transaction can acquire both locks: the attempt transaction has finished.
            tx().executeWithoutResult(status -> {
                deliveries.findByIdForUpdate(fixture.deliveryId()).orElseThrow();
                devices.findByIdForUpdate(fixture.deviceId()).orElseThrow();
            });
            return new NotificationSendResult(NotificationProviderResult.ACCEPTED, null, null, CLOCK.instant(), 1, true);
        });
        worker.dispatchPending();
        worker.dispatchPending(); // Fixture recovery reservation is not due; no memory queue or resend.
        NotificationAttemptSnapshot snapshot = handler.completed.getFirst();
        assertThat(snapshot.target().notificationEventId()).isEqualTo(fixture.eventId());
        assertThat(snapshot.target().alarmId()).isEqualTo(fixture.alarmId());
        assertThat(snapshot.target().eventType()).isEqualTo(TransitEventType.ARRIVED);
        assertThat(snapshot.target().fid()).isEqualTo("rotated-fid");
        assertThat(snapshot.target().registrationRevision()).isEqualTo(2);
        assertThat(snapshot.target().ownershipGeneration()).isZero();
        verify(provider, times(1)).send(snapshot.target().request());
        assertThat(deliveries.count()).isOne();
    }

    @Test
    @DisplayName("시작 실패는 accounting을 rollback하고 불완전한 handler도 전송을 허용하지 않는다")
    void rollback_and_incomplete_start_never_send() {
        Fixture fixture = fixture();
        handler.failStart = true;
        worker.dispatchPending();
        verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("select attempt_count from notification_deliveries where id=?", Integer.class, fixture.deliveryId())).isZero();
        handler.failStart = false;
        handler.omitAccounting = true;
        assertThatThrownBy(() -> attempts.beginAttempt(fixture.deliveryId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Attempt handler did not persist the prepared attempt");
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("시작 commit 응답 유실은 DB에 count가 남아도 Provider를 호출하지 않는다")
    void lost_start_commit_response_is_fail_closed() {
        Fixture fixture = fixture();
        PlatformTransactionManager lostResponse = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return manager.getTransaction(definition);
            }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                throw new TransactionSystemException("Synthetic lost commit response");
            }
            public void rollback(TransactionStatus status) {
                manager.rollback(status);
            }
        };
        worker(attempts(lostResponse), 50).dispatchPending();
        verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("select attempt_count from notification_deliveries where id=?", Integer.class, fixture.deliveryId())).isOne();
        assertThat(deliveries.findDuePendingIds(NOW, PageRequest.of(0, 50))).isEmpty();
    }

    @Test
    @DisplayName("Alarm 삭제로 종료한 Delivery와 다른 attempt에는 late result 및 expiry handler를 적용하지 않는다")
    void terminal_and_stale_attempts_are_not_revived() {
        Fixture deleted = fixture();
        NotificationAttemptSnapshot snapshot = attempts.beginAttempt(deleted.deliveryId()).orElseThrow();
        alarms.delete(deleted.userId(), deleted.alarmId());
        attempts.completeAttempt(snapshot, new NotificationSendResult(NotificationProviderResult.ACCEPTED, null, null, CLOCK.instant(), 1, true));
        attempts.expireBeforeSend(snapshot);
        assertThat(attempts.beginAttempt(deleted.deliveryId())).isEmpty();
        assertThat(handler.completed).isEmpty();
        assertThat(jdbc.queryForObject("select status from notification_deliveries where id=?", String.class, deleted.deliveryId())).isEqualTo("FAILED");
        Fixture changed = fixture();
        NotificationAttemptSnapshot old = attempts.beginAttempt(changed.deliveryId()).orElseThrow();
        jdbc.update("update notification_deliveries set attempt_count=attempt_count+1 where id=?", changed.deliveryId());
        attempts.completeAttempt(old, new NotificationSendResult(NotificationProviderResult.ACCEPTED, null, null, CLOCK.instant(), 1, true));
        assertThat(handler.completed).isEmpty();
        worker.dispatchPending();
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("Provider 접수 후 결과 저장 실패는 시작 count를 유지하고 같은 요청을 즉시 재전송하지 않는다")
    void result_failure_keeps_durable_start_without_resend() {
        Fixture fixture = fixture();
        handler.failResult = true;
        worker.dispatchPending();
        worker.dispatchPending();
        verify(provider, times(1)).send(any());
        assertThat(jdbc.queryForMap("select attempt_count, last_provider_result, status from notification_deliveries where id=?", fixture.deliveryId()))
                .containsEntry("attempt_count", 1).containsEntry("last_provider_result", null).containsEntry("status", "PENDING");
    }

    @Test
    @DisplayName("시작 commit 동안 기한에 도달하면 이미 소비한 attempt 그대로 별도 expiry transaction에 전달한다")
    void expires_after_start_commit_before_provider() {
        Fixture fixture = fixture();
        AtomicReference<Instant> time = new AtomicReference<>(CLOCK.instant());
        Clock mutableClock = mock(Clock.class);
        when(mutableClock.instant()).thenAnswer(call -> time.get());
        PlatformTransactionManager advanceOnCommit = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return manager.getTransaction(definition);
            }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                time.set(NOW.plusSeconds(60).toInstant(ZoneOffset.UTC));
            }
            public void rollback(TransactionStatus status) {
                manager.rollback(status);
            }
        };
        NotificationDispatchAttemptService service = new NotificationDispatchAttemptService(deliveries,
                new NotificationRecipientPreflight(devices), handler, advanceOnCommit, mutableClock);
        new NotificationDispatchWorker(new NotificationDispatchProperties(true, 50, Duration.ofSeconds(1)),
                deliveries, service, provider, mutableClock).dispatchPending();
        verifyNoInteractions(provider);
        assertThat(handler.expired).hasSize(1);
        assertThat(handler.expired.getFirst().target().deliveryId()).isEqualTo(fixture.deliveryId());
        assertThat(handler.expired.getFirst().attemptCount()).isOne();
        assertThat(jdbc.queryForObject("select attempt_count from notification_deliveries where id=?", Integer.class, fixture.deliveryId())).isOne();
    }

    private NotificationDispatchAttemptService attempts(PlatformTransactionManager transactionManager) {
        return new NotificationDispatchAttemptService(deliveries, new NotificationRecipientPreflight(devices), handler,
                transactionManager, CLOCK);
    }

    private NotificationDispatchWorker worker(NotificationDispatchAttemptService service, int batchSize) {
        return new NotificationDispatchWorker(new NotificationDispatchProperties(true, batchSize, Duration.ofSeconds(1)),
                deliveries, service, provider, CLOCK);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(manager);
    }

    private void terminate(long id) {
        tx().executeWithoutResult(status -> deliveries.findByIdForUpdate(id).orElseThrow().terminateDispatch(NOW));
    }

    private Fixture fixture() {
        return tx().execute(status -> {
            User user = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
            entityManager.persist(user);
            Alarm alarm = new Alarm(user, new BusAlarmTarget(TransitProvider.SEOUL_BUS, "route", "stop", 3,
                    "143", "목적지", null, null, null, null, null));
            entityManager.persist(alarm);
            Device device = device(user);
            entityManager.persist(device);
            TransitEvent transit = new TransitEvent(TransitEventType.ARRIVED, "vehicle", UUID.randomUUID(),
                    CLOCK.instant(), null, null, null, null, null, null, null);
            NotificationEvent event = new NotificationEvent(alarm.getId(), user, 0, transit, NOW, NOW);
            entityManager.persist(event);
            NotificationDelivery delivery = new NotificationDelivery(event, device, 0, NOW.plusSeconds(60), NOW);
            entityManager.persist(delivery);
            entityManager.flush();
            return new Fixture(user.getId(), alarm.getId(), event.getId(), device.getId(), delivery.getId());
        });
    }

    private Device device(User user) {
        return new Device(user, UUID.randomUUID().toString(), "a".repeat(64), DevicePlatform.IOS,
                "synthetic-" + UUID.randomUUID(), 0, NOW);
    }

    private record Fixture(long userId, long alarmId, long eventId, long deviceId, long deliveryId) { }

    /**
     * Test-only accounting fixture, not TASK-709 policy. No retry/status/cleanup policy is implemented or asserted.
     * Explicit SQL supplies a valid durable start so real transaction/recipient/provider boundaries can be tested.
     */
    private class FixtureHandler implements NotificationAttemptHandler {
        private final List<Long> eligibilityIds = new ArrayList<>();
        private final List<NotificationRecipientPreflight.Rejection> rejections = new ArrayList<>();
        private final List<NotificationAttemptSnapshot> completed = new ArrayList<>();
        private final List<NotificationAttemptSnapshot> expired = new ArrayList<>();
        private boolean failStart;
        private boolean omitAccounting;
        private boolean failResult;

        public boolean checkEligibility(NotificationDelivery delivery, LocalDateTime now) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            eligibilityIds.add(delivery.getId());
            return now.isBefore(delivery.getExpiresAt()) && delivery.getAttemptCount() < 3;
        }

        public void rejectRecipient(NotificationDelivery delivery, NotificationRecipientPreflight.Rejection reason, LocalDateTime now) {
            rejections.add(reason);
        }

        public NotificationAttemptSnapshot beginAttempt(NotificationDelivery delivery, NotificationDispatchTarget target, LocalDateTime now) {
            int count = delivery.getAttemptCount() + 1;
            if (!omitAccounting) {
                jdbc.update("update notification_deliveries set attempt_count=?, last_attempt_at=?, last_attempt_registration_revision=?, next_attempt_at=?, last_provider_result=null, last_failure_code=null where id=?",
                        count, now, target.registrationRevision(), delivery.getExpiresAt(), delivery.getId());
                entityManager.refresh(delivery);
            }
            if (failStart) {
                throw new IllegalStateException("Synthetic start failure");
            }
            return new NotificationAttemptSnapshot(target, count, now);
        }

        public void completeAttempt(NotificationDelivery delivery, NotificationAttemptSnapshot snapshot, NotificationSendResult result) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(delivery.getAttemptCount()).isEqualTo(snapshot.attemptCount());
            assertThat(result.result()).isEqualTo(NotificationProviderResult.ACCEPTED);
            if (failResult) {
                throw new org.springframework.dao.DataAccessResourceFailureException("Synthetic result failure");
            }
            completed.add(snapshot);
        }

        public void expireBeforeSend(NotificationDelivery delivery, NotificationAttemptSnapshot snapshot, LocalDateTime now) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(now).isAfterOrEqualTo(delivery.getExpiresAt());
            expired.add(snapshot); // Verify orchestration only; no expiry mutation policy is supplied.
        }
    }
}
