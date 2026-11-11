package com.stopbell.notification.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.entity.AdjacentStopSnapshot;
import com.stopbell.alarm.service.AlarmService;
import com.stopbell.notification.client.FcmProviderClient;
import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.notification.entity.*;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import com.stopbell.user.service.AccountDeletionService;
import com.stopbell.user.auth.service.JwtTokenService;
import com.stopbell.user.auth.service.RefreshTokenService;
import com.stopbell.user.auth.exception.InvalidRefreshTokenException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("db")
@Tag("isolated-db")
@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "notification.dispatch.enabled=false",
        "notification.fcm.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationAttemptIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 0, 0);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        SharedMySqlExtension.database(registry, "notification_attempt");
    }

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationDeliveryRepository deliveries;
    @Autowired private DeviceRepository devices;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private AlarmService alarms;
    @Autowired private NotificationRecipientDetachmentService detachment;
    @Autowired private AccountDeletionService deletion;
    @Autowired private JwtTokenService jwt;
    @Autowired private JwtDecoder decoder;
    @Autowired private RefreshTokenService refresh;

    private final Clock clock = mock(Clock.class);
    private AtomicReference<Instant> time;
    private DurableNotificationAttemptHandler handler;
    private NotificationDispatchAttemptService attempts;
    private FcmProviderClient provider;
    private NotificationDispatchWorker worker;

    @BeforeEach
    void set_up() {
        // The class database belongs to the shared Testcontainer; no development DB or Firebase.
        jdbc.update("delete from notification_deliveries");
        jdbc.update("delete from notification_events");
        jdbc.update("delete from devices");
        time = new AtomicReference<>(NOW.toInstant(ZoneOffset.UTC));
        when(clock.instant()).thenAnswer(call -> time.get());
        handler = spy(new DurableNotificationAttemptHandler(new NotificationRetryPolicy(new NotificationRetryProperties(
                3, Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ZERO, Duration.ofSeconds(10)),
                Duration.ofSeconds(15), bound -> 0), devices, clock));
        attempts = service(manager);
        provider = mock(FcmProviderClient.class);
        when(provider.send(any())).thenAnswer(call -> result(NotificationProviderResult.ACCEPTED));
        worker = worker(attempts);
    }

    @Test
    @DisplayName("다른 사용자에게 이전된 Device는 이전 소유자의 계정 삭제에서 보존된다")
    void account_deletion_preserves_transferred_device() {
        Fixture owner = fixture();
        changeRegistration(owner, RegistrationChange.OWNER);
        Long currentOwner = jdbc.queryForObject("select user_id from devices where id=?", Long.class, owner.deviceId());
        assertThat(deletion.delete(owner.userId())).isTrue();
        assertThat(jdbc.queryForObject("select user_id from devices where id=?", Long.class, owner.deviceId()))
                .isEqualTo(currentOwner).isNotEqualTo(owner.userId());
        assertThat(jdbc.queryForObject("select count(*) from notification_events where id=?", Integer.class,
                owner.eventId())).isZero();
    }

    @Test
    @DisplayName("ACTIVE와 FOLLOW_UP Alarm은 회원 삭제에서 모두 정리된다")
    void account_deletion_removes_active_and_follow_up_alarms() {
        Fixture owner = fixture();
        Long followUp = tx().execute(status -> {
            Alarm active = entityManager.find(Alarm.class, owner.alarmId());
            active.activate();
            User user = entityManager.find(User.class, owner.userId());
            Alarm next = new Alarm(user, new BusAlarmTarget(TransitProvider.SEOUL_BUS, "route", "stop", 3,
                    "143", "목적지", null, null, null, null, new AdjacentStopSnapshot("next", 4)));
            entityManager.persist(next);
            next.activate();
            next.startFollowUp("vehicle", UUID.randomUUID(), NOW, NOW.plusMinutes(5));
            entityManager.flush();
            return next.getId();
        });
        assertThat(deletion.delete(owner.userId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from alarms where id in (?,?)", Integer.class,
                owner.alarmId(), followUp)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from bus_alarm_targets where alarm_id in (?,?)", Integer.class,
                owner.alarmId(), followUp)).isZero();
    }

    @Test
    @DisplayName("Refresh rotation이 User 잠금을 기다리는 동안 삭제가 커밋되면 이전 토큰을 거부한다")
    void refresh_waiting_for_deletion_is_rejected() throws Exception {
        Fixture owner = fixture();
        String token = tx().execute(status -> refresh.issue(entityManager.find(User.class, owner.userId())));
        var entered = new CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future = new AtomicReference<java.util.concurrent.Future<?>>();
            tx().executeWithoutResult(status -> {
                entityManager.createQuery("select u from User u where u.id=:id", User.class)
                        .setParameter("id", owner.userId())
                        .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).getSingleResult();
                future.set(executor.submit(() -> { entered.countDown(); return refresh.refresh(token); }));
                await(entered);
                assertThatThrownBy(() -> future.get().get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                assertThat(deletion.delete(owner.userId())).isTrue();
            });
            assertThatThrownBy(() -> future.get().get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InvalidRefreshTokenException.class);
        }
    }

    @Test
    @DisplayName("삭제 트랜잭션의 실패는 User와 Alarm 및 Delivery를 모두 롤백한다")
    void account_deletion_rolls_back() {
        Fixture owner = fixture();
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            assertThat(deletion.delete(owner.userId())).isTrue();
            jdbc.update("insert into refresh_tokens (user_id, token_hash, expires_at, created_at) values (?, ?, ?, ?)",
                    Long.MAX_VALUE, "a".repeat(64), NOW.plusDays(1), NOW);
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from users where id=?", Integer.class, owner.userId())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from alarms where id=?", Integer.class, owner.alarmId())).isOne();
        assertThat(row(owner.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(jdbc.queryForObject("select device_id from notification_deliveries where id=?", Long.class,
                owner.deliveryId())).isEqualTo(owner.deviceId());
    }

    @Test
    @DisplayName("Legacy History는 현재 소유 Alarm에 연결된 행만 삭제하고 확인할 수 없는 행을 보존한다")
    void account_deletion_preserves_unattributed_legacy_history() {
        Fixture owner = fixture();
        Fixture other = fixture();
        jdbc.execute("create table notification_history (id bigint primary key, alarm_id bigint not null)");
        try {
            jdbc.update("insert into notification_history (id, alarm_id) values (1, ?), (2, ?), (3, ?)",
                    owner.alarmId(), other.alarmId(), Long.MAX_VALUE);
            assertThat(deletion.delete(owner.userId())).isTrue();
            assertThat(jdbc.queryForList("select id from notification_history order by id", Long.class))
                    .containsExactly(2L, 3L);
        } finally {
            jdbc.execute("drop table notification_history");
        }
    }

    @Test
    @DisplayName("회원 삭제는 현재 소유 Device를 정리하고 다른 사용자 Event와 Delivery를 보존하며 기존 토큰을 거부한다")
    void account_deletion_preserves_other_event_and_invalidates_tokens() {
        Fixture other = fixture();
        Fixture owner = fixture();
        NotificationAttemptSnapshot started = attempts.beginAttempt(other.deliveryId()).orElseThrow();
        tx().executeWithoutResult(status -> devices.findByIdForUpdate(other.deviceId()).orElseThrow()
                .transferOwnership(entityManager.find(User.class, owner.userId()), "transfer-target", 1, NOW));
        String access = jwt.createAccessToken(owner.userId());
        String otherAccess = jwt.createAccessToken(other.userId());
        String token = tx().execute(status -> refresh.issue(entityManager.find(User.class, owner.userId())));
        jdbc.update("""
                insert into alarm_creation_requests (user_id, client_request_id, request_fingerprint, alarm_id, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?)
                """, owner.userId(), UUID.randomUUID().toString(), new byte[32], owner.alarmId(), NOW, NOW.plusHours(1));
        assertThat(deletion.delete(owner.userId())).isTrue();
        assertThat(deletion.delete(owner.userId())).isFalse();
        assertThatThrownBy(() -> decoder.decode(access)).isInstanceOf(JwtException.class);
        assertThat(decoder.decode(otherAccess).getSubject()).isEqualTo(Long.toString(other.userId()));
        assertThatThrownBy(() -> refresh.refresh(token)).isInstanceOf(InvalidRefreshTokenException.class);
        attempts.completeAttempt(started, result(NotificationProviderResult.ACCEPTED));
        assertThat(row(other.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(row(other.deliveryId()).getDevice()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from notification_events where id=?", Integer.class,
                other.eventId())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from alarms where id=?", Integer.class,
                other.alarmId())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from devices where id=?", Integer.class,
                other.deviceId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification_events where user_id=?", Integer.class,
                owner.userId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from alarm_creation_requests where user_id=?", Integer.class,
                owner.userId())).isZero();
    }

    @Test
    @DisplayName("Apple 철회가 구현되기 전 내부 삭제는 계정과 credential을 보존한다")
    void apple_deletion_is_blocked() {
        Long id = tx().execute(status -> {
            User user = new User(AuthProvider.APPLE, UUID.randomUUID().toString());
            entityManager.persist(user);
            entityManager.flush();
            return user.getId();
        });
        jdbc.update("insert into apple_credentials (user_id, encrypted_refresh_token) values (?, 'synthetic')", id);
        assertThatThrownBy(() -> deletion.delete(id)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from apple_credentials where user_id=?", Integer.class, id)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from users where id=?", Integer.class, id)).isOne();
    }

    @Test
    @DisplayName("신규 Delivery는 Device가 필수이고 DB는 유효하지 않은 Device 참조를 거부하며 NULL 중복은 허용한다")
    void device_constraint_and_null_uniqueness() {
        Fixture fixture = fixture();
        tx().executeWithoutResult(status -> {
            var event = entityManager.find(NotificationEvent.class, fixture.eventId());
            assertThatThrownBy(() -> new NotificationDelivery(event, null, 0, NOW.plusSeconds(60), NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        });
        assertThatThrownBy(() -> jdbc.update("update notification_deliveries set device_id=? where id=?",
                Long.MAX_VALUE, fixture.deliveryId())).isInstanceOf(DataAccessException.class);
        jdbc.update("update notification_deliveries set device_id=null where id=?", fixture.deliveryId());
        jdbc.update("""
                insert into notification_deliveries (notification_event_id, device_id, recipient_ownership_generation,
                    status, attempt_count, next_attempt_at, expires_at, created_at, updated_at)
                select notification_event_id, null, recipient_ownership_generation, status, attempt_count,
                    next_attempt_at, expires_at, created_at, updated_at from notification_deliveries where id=?
                """, fixture.deliveryId());
        assertThat(jdbc.queryForObject("select count(*) from notification_deliveries where notification_event_id=? and device_id is null",
                Integer.class, fixture.eventId())).isEqualTo(2);
    }

    @Test
    @DisplayName("현재 Device 소유자만 Delivery를 해제하며 늦은 Provider 결과는 종료 상태를 되살리지 못한다")
    void detach_stops_pending_and_ignores_late_result() {
        Fixture fixture = fixture();
        NotificationAttemptSnapshot started = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        changeRegistration(fixture, RegistrationChange.OWNER);
        Long owner = jdbc.queryForObject("select user_id from devices where id=?", Long.class, fixture.deviceId());
        var eventBefore = jdbc.queryForMap("select * from notification_events where id=?", fixture.eventId());
        var alarmBefore = jdbc.queryForMap("select * from alarms where id=?", fixture.alarmId());

        assertThat(Boolean.TRUE.equals(tx().execute(status -> detachment.detach(fixture.deliveryId(), fixture.userId())))).isFalse();
        assertThat(Boolean.TRUE.equals(tx().execute(status -> detachment.detach(fixture.deliveryId(), owner)))).isTrue();
        attempts.completeAttempt(started, result(NotificationProviderResult.ACCEPTED));
        assertThat(attempts.beginAttempt(fixture.deliveryId())).isEmpty();
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(row(fixture.deliveryId()).getDevice()).isNull();
        assertThat(row(fixture.deliveryId()).getLastProviderResult()).isNull();
        assertThat(jdbc.queryForMap("select * from notification_events where id=?", fixture.eventId())).isEqualTo(eventBefore);
        assertThat(jdbc.queryForMap("select * from alarms where id=?", fixture.alarmId())).isEqualTo(alarmBefore);
        assertThat(jdbc.update("delete from devices where id=?", fixture.deviceId())).isOne();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationDeliveryStatus.class, names = {"ACCEPTED", "FAILED", "EXPIRED"})
    @DisplayName("참조 해제는 이미 종료된 Delivery의 상태와 전송 결과를 보존한다")
    void detach_preserves_terminal_delivery(NotificationDeliveryStatus terminal) {
        Fixture fixture = fixture();
        NotificationAttemptSnapshot started = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        if (terminal == NotificationDeliveryStatus.EXPIRED) {
            advance(60);
            attempts.expireDuePending(fixture.deliveryId());
        } else {
            attempts.completeAttempt(started, result(terminal == NotificationDeliveryStatus.ACCEPTED
                    ? NotificationProviderResult.ACCEPTED : NotificationProviderResult.PERMANENT_REQUEST));
        }
        var before = jdbc.queryForMap("select * from notification_deliveries where id=?", fixture.deliveryId());
        assertThat(Boolean.TRUE.equals(tx().execute(status -> detachment.detach(fixture.deliveryId(), fixture.userId())))).isTrue();
        var after = jdbc.queryForMap("select * from notification_deliveries where id=?", fixture.deliveryId());
        assertThat(after.remove("device_id")).isNull();
        before.remove("device_id");
        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("참조 해제 트랜잭션이 롤백되면 PENDING 상태와 Device 참조가 모두 복원된다")
    void detach_rolls_back_atomically() {
        Fixture fixture = fixture();
        tx().executeWithoutResult(status -> {
            assertThat(detachment.detach(fixture.deliveryId(), fixture.userId())).isTrue();
            deliveries.flush();
            status.setRollbackOnly();
        });
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(jdbc.queryForObject("select device_id from notification_deliveries where id=?", Long.class,
                fixture.deliveryId())).isEqualTo(fixture.deviceId());
    }

    @Test
    @DisplayName("Device 참조가 해제된 Delivery는 재전송하지 않고 종료한다")
    void detached_recipient_is_not_dispatched() {
        Fixture fixture = fixture();
        jdbc.update("update notification_deliveries set device_id=null where id=?", fixture.deliveryId());
        jdbc.update("delete from devices where id=?", fixture.deviceId());

        worker.dispatchPending();

        verifyNoInteractions(provider);
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(row(fixture.deliveryId()).getLastFailureCode()).isEqualTo("DISPATCH_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("select count(*) from notification_events where id=?", Integer.class,
                fixture.eventId())).isOne();
    }

    @Test
    @DisplayName("production handler의 시작 commit 뒤 transaction 밖에서 원래 Event와 기한으로 Provider를 한 번 호출한다")
    void worker_commits_before_provider_and_persists_acceptance() {
        Fixture fixture = fixture();
        when(provider.send(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            NotificationDelivery started = row(fixture.deliveryId());
            assertThat(started.getAttemptCount()).isOne();
            assertThat(started.getLastAttemptAt()).isEqualTo(NOW);
            assertThat(started.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(25));
            assertThat(started.getLastProviderResult()).isNull();
            return result(NotificationProviderResult.ACCEPTED);
        });
        worker.dispatchPending();
        worker.dispatchPending();
        NotificationDelivery accepted = row(fixture.deliveryId());
        assertThat(accepted.getStatus()).isEqualTo(NotificationDeliveryStatus.ACCEPTED);
        assertThat(accepted.getProviderAcceptedAt()).isEqualTo(NOW);
        assertThat(accepted.getLastFailureCode()).isNull();
        assertThat(accepted.getExpiresAt()).isEqualTo(NOW.plusSeconds(60));
        verify(provider, times(1)).send(new NotificationDispatchTarget(fixture.deliveryId(), fixture.eventId(),
                fixture.alarmId(), TransitEventType.ARRIVED, fixture.deviceId(), fixture.userId(), 0, 0,
                fixture.fid(), NOW.plusSeconds(60)).request());
    }

    @Test
    @DisplayName("시작 transaction 실패는 count와 예약을 rollback하고 외부 전송을 금지한다")
    void start_rollback_does_not_send() {
        Fixture fixture = fixture();
        worker(service(failingCommit(1, false, true))).dispatchPending();
        verifyNoInteractions(provider);
        assertThat(row(fixture.deliveryId()).getAttemptCount()).isZero();
        assertThat(row(fixture.deliveryId()).getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("시작 commit 응답 유실은 호출하지 않아도 count를 소비하고 restart recovery에서도 reset하지 않는다")
    void lost_start_commit_preserves_recovery_budget() {
        Fixture fixture = fixture();
        worker(service(failingCommit(1, true, true))).dispatchPending();
        verifyNoInteractions(provider);
        assertThat(row(fixture.deliveryId()).getAttemptCount()).isOne();
        assertThat(row(fixture.deliveryId()).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(25));
        NotificationDispatchAttemptService restarted = service(manager);
        assertThat(restarted.beginAttempt(fixture.deliveryId())).isEmpty();
        advance(25);
        NotificationAttemptSnapshot recovery = restarted.beginAttempt(fixture.deliveryId()).orElseThrow();
        assertThat(recovery.attemptCount()).isEqualTo(2);
        assertThat(row(fixture.deliveryId()).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(50));
        advance(50);
        assertThat(service(manager).beginAttempt(fixture.deliveryId()).orElseThrow().attemptCount()).isEqualTo(3);
        assertThat(row(fixture.deliveryId()).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        advance(60);
        assertThat(service(manager).beginAttempt(fixture.deliveryId())).isEmpty();
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(row(fixture.deliveryId()).getAttemptCount()).isEqualTo(3);
        assertThat(row(fixture.deliveryId()).getLastProviderResult()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationProviderResult.class, names = {"ACCEPTED", "INVALID_TARGET", "RETRYABLE"})
    @DisplayName("결과 commit 응답 유실은 current read로 확인하고 같은 결과 및 cleanup을 중복 적용하지 않는다")
    void lost_result_commit_is_idempotent(NotificationProviderResult kind) {
        Fixture fixture = fixture();
        when(provider.send(any())).thenAnswer(call -> result(kind));
        worker(service(failingCommit(2, true, true))).dispatchPending();
        verify(provider, times(1)).send(any());
        verify(handler, times(1)).completeAttempt(any(), any(), any());
        assertThat(row(fixture.deliveryId()).getLastProviderResult()).isEqualTo(kind);
        assertThat(row(fixture.deliveryId()).getAttemptCount()).isOne();
        if (kind == NotificationProviderResult.INVALID_TARGET) {
            assertThat(devices.findById(fixture.deviceId()).orElseThrow().isEnabled()).isFalse();
        }
    }

    @Test
    @DisplayName("결과 commit 전 rollback은 동일 snapshot과 결과만 재반영하며 cleanup도 원자적으로 재시도한다")
    void result_rollback_reapplies_without_provider_resend() {
        Fixture fixture = fixture();
        when(provider.send(any())).thenAnswer(call -> result(NotificationProviderResult.INVALID_TARGET));
        worker(service(failingCommit(2, false, true))).dispatchPending();
        verify(provider, times(1)).send(any());
        verify(handler, times(2)).completeAttempt(any(), any(), any());
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(devices.findById(fixture.deviceId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("결과 DB 재반영 두 번이 모두 실패하면 cycle을 종료하고 미시작 Delivery를 소비하지 않는다")
    void persistent_result_failure_stops_cycle() {
        Fixture first = fixture();
        Fixture second = fixture();
        worker(service(failingCommit(2, false, false))).dispatchPending();
        verify(provider, times(1)).send(any());
        verify(handler, times(2)).completeAttempt(any(), any(), any());
        assertThat(row(first.deliveryId()).getLastProviderResult()).isNull();
        assertThat(row(first.deliveryId()).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(25));
        assertThat(row(second.deliveryId()).getAttemptCount()).isZero();
        assertThat(devices.findById(first.deviceId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("실제 UNREGISTERED cleanup은 revision과 generation 및 소유자와 등록 시각 및 credential을 보존한다")
    void cleanup_preserves_client_registration_metadata() {
        Fixture fixture = fixture();
        Device before = devices.findById(fixture.deviceId()).orElseThrow();
        NotificationAttemptSnapshot snapshot = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        attempts.completeAttempt(snapshot, result(NotificationProviderResult.INVALID_TARGET));
        Device after = devices.findById(fixture.deviceId()).orElseThrow();
        assertThat(after.isEnabled()).isFalse();
        assertThat(after.getCurrentPushTargetId()).isNull();
        assertThat(after.getRegistrationRevision()).isEqualTo(before.getRegistrationRevision());
        assertThat(after.getOwnershipGeneration()).isEqualTo(before.getOwnershipGeneration());
        assertThat(after.getLastRegisteredAt()).isEqualTo(before.getLastRegisteredAt());
        assertThat(after.getUser().getId()).isEqualTo(before.getUser().getId());
        assertThat(after.getInstallationCredentialHash()).isEqualTo(before.getInstallationCredentialHash());
        LocalDateTime updatedAt = after.getUpdatedAt();
        attempts.completeAttempt(snapshot, result(NotificationProviderResult.INVALID_TARGET));
        assertThat(devices.findById(fixture.deviceId()).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
    }

    enum RegistrationChange { FID_ROTATION, SAME_FID_REVISION, OWNER, RETURNED_OWNER, DISABLED, CASE_CHANGE }

    @ParameterizedTest
    @EnumSource(RegistrationChange.class)
    @DisplayName("과거 UNREGISTERED는 FID와 revision과 owner 및 generation 및 enabled가 달라진 등록을 disable하지 않는다")
    void stale_cleanup_is_rejected(RegistrationChange change) {
        Fixture fixture = fixture();
        NotificationAttemptSnapshot snapshot = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        changeRegistration(fixture, change);
        Device before = devices.findById(fixture.deviceId()).orElseThrow();
        attempts.completeAttempt(snapshot, result(NotificationProviderResult.INVALID_TARGET));
        Device after = devices.findById(fixture.deviceId()).orElseThrow();
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(after.isEnabled()).isEqualTo(before.isEnabled());
        assertThat(after.getCurrentPushTargetId()).isEqualTo(before.getCurrentPushTargetId());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.getRegistrationRevision()).isEqualTo(before.getRegistrationRevision());
        assertThat(after.getOwnershipGeneration()).isEqualTo(before.getOwnershipGeneration());
    }

    @ParameterizedTest
    @EnumSource(value = RegistrationChange.class, names = {"OWNER", "RETURNED_OWNER", "DISABLED"})
    @DisplayName("production preflight는 소유권 변경과 A→B→A 및 disabled를 local 종료하고 count를 소비하지 않는다")
    void preflight_rejection_terminates_locally(RegistrationChange change) {
        Fixture fixture = fixture();
        changeRegistration(fixture, change);
        worker.dispatchPending();
        verifyNoInteractions(provider);
        NotificationDelivery delivery = row(fixture.deliveryId());
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(delivery.getAttemptCount()).isZero();
        assertThat(delivery.getLastProviderResult()).isNull();
        assertThat(delivery.getLastFailureCode()).isEqualTo(switch (change) {
            case OWNER -> "RECIPIENT_OWNER_CHANGED";
            case RETURNED_OWNER -> "RECIPIENT_GENERATION_CHANGED";
            default -> "RECIPIENT_DISABLED";
        });
    }

    @Test
    @DisplayName("만료 뒤 UNREGISTERED도 EXPIRED로 기록하며 동일 current 등록만 cleanup한다")
    void expired_unregistered_may_cleanup() {
        Fixture fixture = fixture();
        NotificationAttemptSnapshot snapshot = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        advance(60);
        attempts.completeAttempt(snapshot, result(NotificationProviderResult.INVALID_TARGET));
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(row(fixture.deliveryId()).getLastProviderResult()).isEqualTo(NotificationProviderResult.INVALID_TARGET);
        assertThat(devices.findById(fixture.deviceId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("cleanup 후 transaction 실패는 Device disable과 Delivery 결과를 함께 rollback한다")
    void cleanup_and_delivery_rollback_together() {
        Fixture fixture = fixture();
        NotificationAttemptSnapshot snapshot = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        doAnswer(call -> {
            call.callRealMethod();
            entityManager.flush();
            throw new TransactionSystemException("synthetic failure after flush");
        }).when(handler).completeAttempt(any(), any(), any());
        assertThatThrownBy(() -> attempts.completeAttempt(snapshot, result(NotificationProviderResult.INVALID_TARGET)))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(row(fixture.deliveryId()).getLastProviderResult()).isNull();
        assertThat(devices.findById(fixture.deviceId()).orElseThrow().isEnabled()).isTrue();
        assertThat(devices.findById(fixture.deviceId()).orElseThrow().getCurrentPushTargetId()).isEqualTo(fixture.fid());
    }

    @Test
    @DisplayName("현재 Device lock을 기다린 cleanup은 이미 commit된 새로운 등록을 읽어 stale 결과를 거부한다")
    void cleanup_waits_for_registration_current_lock() throws Exception {
        Fixture fixture = fixture();
        NotificationAttemptSnapshot snapshot = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread registration = Thread.startVirtualThread(() -> {
            try {
                tx().executeWithoutResult(status -> {
                    devices.findByIdForUpdate(fixture.deviceId()).orElseThrow().register("new-registration", 1, NOW);
                    entityManager.flush();
                    registered.countDown();
                    await(release);
                });
            } catch (Throwable failure) { error.set(failure); }
        });
        assertThat(registered.await(5, TimeUnit.SECONDS)).isTrue();
        doAnswer(call -> {
            cleaning.countDown();
            return call.callRealMethod();
        }).when(handler).completeAttempt(any(), any(), any());
        Thread cleanup = Thread.startVirtualThread(() -> {
            try {
                attempts.completeAttempt(snapshot, result(NotificationProviderResult.INVALID_TARGET));
            } catch (Throwable failure) { error.set(failure); }
        });
        try {
            assertThat(cleaning.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            registration.join(5_000);
            cleanup.join(5_000);
        }
        assertThat(error.get()).isNull();
        assertThat(registration.isAlive()).isFalse();
        assertThat(cleanup.isAlive()).isFalse();
        Device device = devices.findById(fixture.deviceId()).orElseThrow();
        assertThat(device.isEnabled()).isTrue();
        assertThat(device.getCurrentPushTargetId()).isEqualTo("new-registration");
    }

    @Test
    @DisplayName("다음 attempt가 시작된 뒤 이전 count와 시각 및 revision의 결과는 모두 무변경이다")
    void late_attempt_results_are_ignored() {
        Fixture fixture = fixture();
        NotificationAttemptSnapshot first = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        advance(25);
        NotificationAttemptSnapshot second = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        attempts.completeAttempt(first, result(NotificationProviderResult.INVALID_TARGET));
        attempts.completeAttempt(new NotificationAttemptSnapshot(second.target(), second.attemptCount(), NOW), result(NotificationProviderResult.ACCEPTED));
        NotificationDispatchTarget target = second.target();
        NotificationDispatchTarget wrongRevision = new NotificationDispatchTarget(target.deliveryId(), target.notificationEventId(),
                target.alarmId(), target.eventType(), target.deviceId(), target.eventOwnerId(), target.ownershipGeneration(),
                target.registrationRevision() + 1, target.fid(), target.expiresAt());
        attempts.completeAttempt(new NotificationAttemptSnapshot(wrongRevision, 2, second.lastAttemptAt()), result(NotificationProviderResult.ACCEPTED));
        assertThat(row(fixture.deliveryId()).getLastProviderResult()).isNull();
        assertThat(row(fixture.deliveryId()).getAttemptCount()).isEqualTo(2);
        assertThat(devices.findById(fixture.deviceId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("Alarm 삭제로 종료된 Delivery는 late 접수나 UNREGISTERED cleanup으로 부활하지 않는다")
    void alarm_delete_blocks_late_result_and_cleanup() {
        Fixture fixture = fixture();
        NotificationAttemptSnapshot snapshot = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        alarms.delete(fixture.userId(), fixture.alarmId());
        attempts.completeAttempt(snapshot, result(NotificationProviderResult.INVALID_TARGET));
        attempts.completeAttempt(snapshot, result(NotificationProviderResult.ACCEPTED));
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(row(fixture.deliveryId()).getLastFailureCode()).isEqualTo("DISPATCH_NOT_ALLOWED");
        assertThat(row(fixture.deliveryId()).getLastProviderResult()).isNull();
        assertThat(devices.findById(fixture.deviceId()).orElseThrow().isEnabled()).isTrue();
        assertThat(attempts.beginAttempt(fixture.deliveryId())).isEmpty();
    }

    @Test
    @DisplayName("restart는 retry 예약과 expiresAt을 보존하고 다음 due에는 current FID를 사용한다")
    void restart_uses_durable_retry_and_current_target() {
        Fixture fixture = fixture();
        NotificationAttemptSnapshot first = attempts.beginAttempt(fixture.deliveryId()).orElseThrow();
        attempts.completeAttempt(first, result(NotificationProviderResult.RETRYABLE));
        changeRegistration(fixture, RegistrationChange.FID_ROTATION);
        advance(9);
        assertThat(service(manager).beginAttempt(fixture.deliveryId())).isEmpty();
        advance(10);
        NotificationAttemptSnapshot second = service(manager).beginAttempt(fixture.deliveryId()).orElseThrow();
        assertThat(second.target().fid()).isEqualTo("new-registration");
        assertThat(second.target().registrationRevision()).isOne();
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(second.target().expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(row(fixture.deliveryId()).getLastProviderResult()).isNull();
        attempts.completeAttempt(second, result(NotificationProviderResult.AMBIGUOUS_TIMEOUT));
        assertThat(row(fixture.deliveryId()).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    @DisplayName("장시간 중단 뒤 pending은 expiry가 budget과 recipient보다 우선하며 Provider 없이 종료한다")
    void long_restart_expires_without_send() {
        Fixture fixture = fixture();
        attempts.beginAttempt(fixture.deliveryId());
        advance(3600);
        worker.dispatchPending();
        verifyNoInteractions(provider);
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(row(fixture.deliveryId()).getExpiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(row(fixture.deliveryId()).getAttemptCount()).isOne();
    }

    @Test
    @DisplayName("최초 미전송 expiry와 budget 소진은 가짜 Provider 결과를 만들지 않는다")
    void initial_expiry_and_attempt_limit_are_local() {
        Fixture expired = fixture();
        Fixture limited = fixture();
        jdbc.update("update notification_deliveries set attempt_count=3, last_attempt_at=?, last_attempt_registration_revision=0 where id=?",
                NOW, limited.deliveryId());
        assertThat(attempts.beginAttempt(limited.deliveryId())).isEmpty();
        assertThat(row(limited.deliveryId()).getLastFailureCode()).isEqualTo("ATTEMPT_LIMIT_REACHED");
        advance(60);
        worker.dispatchPending();
        assertThat(row(expired.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(row(expired.deliveryId()).getAttemptCount()).isZero();
        assertThat(row(expired.deliveryId()).getLastProviderResult()).isNull();
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("시작 commit 직후 기한 도달 시 이미 소비한 count를 유지하고 별도 local expiry로 종료한다")
    void expires_between_commit_and_send() {
        Fixture fixture = fixture();
        PlatformTransactionManager advancing = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) { manager.commit(status); advance(60); }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        worker(service(advancing)).dispatchPending();
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(row(fixture.deliveryId()).getAttemptCount()).isOne();
        assertThat(row(fixture.deliveryId()).getLastProviderResult()).isNull();
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("multi-device 일부 접수는 보존하고 나머지만 독립 retry하며 새 recipient를 추가하지 않는다")
    void partial_device_success_is_independent() {
        Fixture fixture = fixture();
        long otherDelivery = tx().execute(status -> {
            Device device = device(entityManager.find(User.class, fixture.userId()));
            entityManager.persist(device);
            NotificationDelivery delivery = new NotificationDelivery(entityManager.find(NotificationEvent.class, fixture.eventId()),
                    device, 0, NOW.plusSeconds(60), NOW);
            entityManager.persist(delivery);
            entityManager.flush();
            return delivery.getId();
        });
        when(provider.send(any())).thenReturn(result(NotificationProviderResult.ACCEPTED), result(NotificationProviderResult.RETRYABLE));
        worker.dispatchPending();
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.ACCEPTED);
        assertThat(row(otherDelivery).getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        tx().executeWithoutResult(status -> entityManager.persist(device(entityManager.find(User.class, fixture.userId()))));
        advance(10);
        when(provider.send(any())).thenReturn(result(NotificationProviderResult.ACCEPTED));
        worker(service(manager)).dispatchPending();
        assertThat(row(fixture.deliveryId()).getAttemptCount()).isOne();
        assertThat(row(otherDelivery).getAttemptCount()).isEqualTo(2);
        assertThat(deliveries.count()).isEqualTo(2);
        verify(provider, times(3)).send(any());
    }

    @Test
    @DisplayName("인증 초기화 실패는 미시작 row를 보존하고 후속 cycle은 expiry만 처리한다")
    void configuration_failure_blocks_new_attempts_but_sweeps_expiry() {
        Fixture first = fixture();
        Fixture second = fixture();
        when(provider.send(any())).thenReturn(new NotificationSendResult(NotificationProviderResult.CONFIGURATION,
                "AUTH_INITIALIZATION_FAILED", Duration.ZERO, time.get(), 1, false));
        worker.dispatchPending();
        worker.dispatchPending();
        assertThat(row(first.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(row(second.deliveryId()).getAttemptCount()).isZero();
        assertThat(row(second.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        advance(60);
        worker.dispatchPending();
        assertThat(row(second.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(row(second.deliveryId()).getAttemptCount()).isZero();
        verify(provider, times(1)).send(any());
    }

    @Test
    @DisplayName("blocked expiry 조회는 앞선 fresh pending이 batch를 채워도 만료 row를 숨기지 않는다")
    void configuration_blocked_sweep_does_not_starve() {
        Fixture first = fixture();
        when(provider.send(any())).thenAnswer(call -> result(NotificationProviderResult.CONFIGURATION));
        worker.dispatchPending();
        Fixture fresh = fixture();
        Fixture expired = fixture();
        jdbc.update("update notification_deliveries set expires_at=? where id=?", NOW.plusSeconds(120), fresh.deliveryId());
        advance(60);
        assertThat(deliveries.findExpiredPendingIds(NOW.plusSeconds(60), org.springframework.data.domain.PageRequest.of(0, 1)))
                .containsExactly(expired.deliveryId());
        worker.dispatchPending();
        assertThat(row(fresh.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(row(expired.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(row(first.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        verify(provider, times(1)).send(any());
    }

    @Test
    @DisplayName("MySQL CHECK는 잘못된 terminal 예약 및 count와 attempt 필드 및 접수 조합을 실제로 거부한다")
    void mysql_checks_guard_operational_invariants() {
        Fixture fixture = fixture();
        for (String assignment : new String[] {"status='FAILED', last_failure_code='SAFE'", "attempt_count=1",
                "status='ACCEPTED', next_attempt_at=null", "next_attempt_at=expires_at + interval 1 second"}) {
            assertThatThrownBy(() -> jdbc.update("update notification_deliveries set " + assignment + " where id=?", fixture.deliveryId()))
                    .isInstanceOf(DataAccessException.class).rootCause()
                    .isInstanceOf(java.sql.SQLException.class)
                    .satisfies(failure -> assertThat(((java.sql.SQLException) failure).getErrorCode()).isEqualTo(3819));
        }
        assertThat(row(fixture.deliveryId()).getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(row(fixture.deliveryId()).getAttemptCount()).isZero();
    }

    private NotificationDispatchAttemptService service(PlatformTransactionManager transactionManager) {
        return new NotificationDispatchAttemptService(deliveries, new NotificationRecipientPreflight(devices), handler, transactionManager, clock);
    }

    private NotificationDispatchWorker worker(NotificationDispatchAttemptService service) {
        return new NotificationDispatchWorker(new NotificationDispatchProperties(true, 50, Duration.ofSeconds(1)), deliveries, service, provider, clock);
    }

    private PlatformTransactionManager failingCommit(int failAt, boolean afterCommit, boolean once) {
        AtomicInteger count = new AtomicInteger();
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                int current = count.incrementAndGet();
                if (current == failAt || !once && current > failAt) {
                    if (afterCommit) { manager.commit(status); } else { manager.rollback(status); }
                    throw new TransactionSystemException("synthetic commit response failure");
                }
                manager.commit(status);
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
    }

    private NotificationDelivery row(long id) { return deliveries.findById(id).orElseThrow(); }
    private TransactionTemplate tx() { return new TransactionTemplate(manager); }
    private void advance(long seconds) { time.set(NOW.plusSeconds(seconds).toInstant(ZoneOffset.UTC)); }

    private void changeRegistration(Fixture fixture, RegistrationChange change) {
        if (change == RegistrationChange.CASE_CHANGE) {
            // Isolate exact FID comparison from revision checks; schema allows this case change.
            jdbc.update("update devices set current_push_target_id=? where id=?", fixture.fid().toUpperCase(java.util.Locale.ROOT), fixture.deviceId());
            return;
        }
        tx().executeWithoutResult(status -> {
            Device device = devices.findByIdForUpdate(fixture.deviceId()).orElseThrow();
            if (change == RegistrationChange.DISABLED) { device.disable(1); return; }
            if (change == RegistrationChange.FID_ROTATION) { device.register("new-registration", 1, NOW); return; }
            if (change == RegistrationChange.SAME_FID_REVISION) { device.register(fixture.fid(), 1, NOW); return; }
            User other = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
            entityManager.persist(other);
            device.transferOwnership(other, "other-registration", 1, NOW);
            if (change == RegistrationChange.RETURNED_OWNER) {
                device.transferOwnership(entityManager.find(User.class, fixture.userId()), fixture.fid(), 2, NOW);
            }
        });
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
            NotificationEvent event = new NotificationEvent(alarm.getId(), user, 0,
                    new TransitEvent(TransitEventType.ARRIVED, "vehicle", UUID.randomUUID(),
                            NOW.toInstant(ZoneOffset.UTC), null, null, null, null, null, null, null), NOW, NOW);
            entityManager.persist(event);
            NotificationDelivery delivery = new NotificationDelivery(event, device, 0, NOW.plusSeconds(60), NOW);
            entityManager.persist(delivery);
            entityManager.flush();
            return new Fixture(user.getId(), alarm.getId(), event.getId(), device.getId(), delivery.getId(), device.getCurrentPushTargetId());
        });
    }

    private Device device(User user) {
        return new Device(user, UUID.randomUUID().toString(), "a".repeat(64), DevicePlatform.IOS,
                "synthetic-" + UUID.randomUUID(), 0, NOW);
    }

    private NotificationSendResult result(NotificationProviderResult kind) {
        String code = switch (kind) {
            case ACCEPTED -> null;
            case INVALID_TARGET -> "UNREGISTERED";
            case RETRYABLE -> "UNAVAILABLE";
            case CONFIGURATION -> "AUTH_INITIALIZATION_FAILED";
            case PERMANENT_REQUEST -> "INVALID_ARGUMENT_UNCLASSIFIED";
            case AMBIGUOUS_TIMEOUT -> "UNKNOWN_ACCEPTANCE";
        };
        return new NotificationSendResult(kind, code, Duration.ofSeconds(10), time.get(), 1, kind != NotificationProviderResult.CONFIGURATION);
    }

    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }

    private record Fixture(long userId, long alarmId, long eventId, long deviceId, long deliveryId, String fid) { }
}
