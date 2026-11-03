package com.stopbell.user.service;

import java.time.Clock;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.boarding.entity.BoardingTraversalSnapshot;
import com.stopbell.boarding.entity.BoardingCheckpoint;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationEventRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.notification.entity.NotificationDeliveryStatus;
import com.stopbell.notification.entity.NotificationDelivery;
import com.stopbell.notification.entity.NotificationEvent;
import com.stopbell.notification.entity.DevicePlatform;
import com.stopbell.notification.entity.Device;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.user.auth.exception.InvalidRefreshTokenException;
import org.springframework.dao.DataAccessException;
import java.time.temporal.ChronoUnit;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.alarm.service.AlarmService;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.user.auth.apple.AppleCredentialCipher;
import com.stopbell.user.auth.apple.AppleTokenRevocation;
import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.auth.identity.GoogleIdentityVerifier;
import com.stopbell.user.auth.service.JwtTokenService;
import com.stopbell.user.auth.service.LoginService;
import com.stopbell.user.auth.service.RefreshTokenService;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = "account.deletion.recovery-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AccountWithdrawalIntegrationTest.CipherConfiguration.class)
class AccountWithdrawalIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        SharedMySqlExtension.database(registry, "account_withdrawal");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CipherConfiguration {
        @Bean AppleCredentialCipher appleCredentialCipher() {
            return new AppleCredentialCipher("test", Map.of("test", Base64.getEncoder().encodeToString(new byte[32])));
        }
    }

    @Autowired private AccountWithdrawalService withdrawal;
    @Autowired private BoardingSessionRepository sessions;
    @Autowired private DeviceRepository devices;
    @Autowired private NotificationEventRepository events;
    @Autowired private NotificationDeliveryRepository deliveries;
    @Autowired private AppleCredentialCipher cipher;
    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService tokens;
    @Autowired private LoginService login;
    @Autowired private RefreshTokenService refresh;
    @Autowired private AlarmRepository alarms;
    @Autowired private AlarmService alarmService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private Clock jwtClock;
    @MockitoSpyBean private UserRepository users;
    @MockitoBean private AppleTokenRevocation revocation;
    @MockitoBean private GoogleIdentityVerifier google;

    @BeforeEach
    void setup() {
        reset(revocation);
        when(revocation.revoke(anyString())).thenReturn(AppleTokenRevocation.Outcome.REVOKED);
    }

    @Test
    @DisplayName("Google 계정을 Principal 기준으로 삭제하며 다른 사용자와 기존 Token 무효화를 검증한다")
    void delete_google_account_and_reject_old_tokens() throws Exception {
        var owner = user(AuthProvider.GOOGLE);
        var other = user(AuthProvider.GOOGLE);
        String access = tokens.createAccessToken(owner.getId());
        String token = tx().execute(status -> refresh.issue(owner));
        mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer " + access)
                        .contentType("application/json").content("{\"userId\":" + other.getId() + "}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(users.existsById(owner.getId())).isFalse();
        assertThat(users.existsById(other.getId())).isTrue();
        assertThatThrownBy(() -> refresh.refresh(token)).isInstanceOf(InvalidRefreshTokenException.class);
        mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(revocation);
    }

    @Test
    @DisplayName("유효한 Access Token 없이 탈퇴할 수 없고 임의 사용자 경로도 제공하지 않는다")
    void require_authentication_and_owner_only_path() throws Exception {
        var owner = user(AuthProvider.GOOGLE);
        mvc.perform(delete("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/users/" + owner.getId())
                .header("Authorization", "Bearer " + tokens.createAccessToken(owner.getId())))
                .andExpect(status().isNotFound());
        assertThat(users.existsById(owner.getId())).isTrue();
    }

    @Test
    @DisplayName("저장된 Apple 토큰을 복호화해 DB Transaction 밖에서 철회하고 내부 데이터를 삭제한다")
    void revoke_real_stored_credential_then_delete() throws Exception {
        var owner = apple();
        when(revocation.revoke("synthetic-refresh")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(state(owner)).isEqualTo("PENDING");
            // A separate connection can lock the user while the external call is in flight.
            tx().executeWithoutResult(status -> users.findByIdForUpdate(owner.getId()).orElseThrow());
            assertThat(count("apple_credentials", owner)).isOne();
            return AppleTokenRevocation.Outcome.REVOKED;
        });
        mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer " + tokens.createAccessToken(owner.getId())))
                .andExpect(status().isNoContent());
        verify(revocation).revoke("synthetic-refresh");
        assertThat(users.existsById(owner.getId())).isFalse();
        assertThat(count("apple_credentials", owner)).isZero();
        assertThat(count("account_deletions", owner)).isZero();
    }

    @Test
    @DisplayName("토큰이 없는 Apple 계정도 로컬 삭제를 완료하되 수동 연결 해제 필요를 구분한다")
    void missing_token_requires_manual_revocation() throws Exception {
        assertManual(user(AuthProvider.APPLE));
        verifyNoInteractions(revocation);
    }

    @Test
    @DisplayName("복호화할 수 없는 토큰은 철회 성공을 주장하지 않고 로컬 삭제와 수동 안내를 반환한다")
    void corrupt_token_requires_manual_revocation() throws Exception {
        var owner = apple();
        jdbc.update("update apple_credentials set encrypted_refresh_token=? where user_id=?", "corrupt", owner.getId());
        assertManual(owner);
        verifyNoInteractions(revocation);
    }

    @Test
    @DisplayName("알 수 없는 암호화 키의 토큰도 영구 삭제 차단 없이 수동 해제 결과로 구분한다")
    void unavailable_encryption_key_requires_manual_revocation() throws Exception {
        var owner = apple();
        String encrypted = jdbc.queryForObject("select encrypted_refresh_token from apple_credentials where user_id=?", String.class, owner.getId());
        jdbc.update("update apple_credentials set encrypted_refresh_token=? where user_id=?", encrypted.replace("v1.test.", "v1.retired."), owner.getId());
        assertManual(owner);
    }

    @Test
    @DisplayName("네트워크 장애는 202와 불확실 상태로 반환하며 토큰을 보존해 같은 요청으로 재시도한다")
    void uncertain_result_retains_credential_and_retries() throws Exception {
        var owner = apple();
        String access = tokens.createAccessToken(owner.getId());
        when(revocation.revoke(anyString())).thenReturn(AppleTokenRevocation.Outcome.UNCERTAIN,
                AppleTokenRevocation.Outcome.REVOKED);
        mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isAccepted()).andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("status").value("PENDING")).andExpect(jsonPath("appleRevocation").value("UNCONFIRMED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-refresh"))));
        assertThat(state(owner)).isEqualTo("PENDING");
        assertThat(count("apple_credentials", owner)).isOne();
        mvc.perform(get("/api/v1/alarms").header("Authorization", "Bearer " + access))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isNoContent());
        verify(revocation, times(2)).revoke("synthetic-refresh");
    }

    @ParameterizedTest
    @EnumSource(value = AppleTokenRevocation.Outcome.class, names = {"CONFIGURATION_ERROR", "REJECTED"})
    @DisplayName("Apple 설정 오류와 거절을 구분하며 삭제 완료로 응답하지 않는다")
    void classify_revoke_errors(AppleTokenRevocation.Outcome outcome) throws Exception {
        var owner = apple();
        when(revocation.revoke(anyString())).thenReturn(outcome);
        String code = outcome == AppleTokenRevocation.Outcome.CONFIGURATION_ERROR
                ? "ACCOUNT_DELETION_CONFIGURATION_ERROR" : "ACCOUNT_DELETION_REJECTED";
        mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer " + tokens.createAccessToken(owner.getId())))
                .andExpect(status().is(outcome == AppleTokenRevocation.Outcome.CONFIGURATION_ERROR ? 503 : 502))
                .andExpect(jsonPath("code").value(code));
        assertThat(state(owner)).isEqualTo("PENDING");
        assertThat(count("apple_credentials", owner)).isOne();
    }

    @Test
    @DisplayName("철회 성공 후 실제 DB 삭제 실패 시 데이터와 성공 상태를 보존하고 재철회 없이 삭제한다")
    void database_failure_after_revocation_is_recoverable() throws Exception {
        var owner = apple();
        Alarm alarm = alarm(owner);
        jdbc.execute("create trigger reject_account_delete before delete on users for each row signal sqlstate '45000' set message_text='synthetic failure'");
        try {
            mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer " + tokens.createAccessToken(owner.getId())))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("code").value("INTERNAL_SERVER_ERROR"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic failure"))));
            assertThat(users.existsById(owner.getId())).isTrue();
            assertThat(count("apple_credentials", owner)).isOne();
            assertThat(alarms.existsById(alarm.getId())).isTrue();
            assertThat(state(owner)).isEqualTo("REVOKED");
        } finally { jdbc.execute("drop trigger reject_account_delete"); }
        assertThat(withdrawal.withdraw(owner.getId())).isEqualTo(AccountWithdrawalService.Result.DELETED);
        verify(revocation, times(1)).revoke("synthetic-refresh");
        assertThat(alarms.existsById(alarm.getId())).isFalse();
    }

    @Test
    @DisplayName("철회 결과 저장 실패는 PENDING과 토큰을 남기며 이미 철회된 토큰으로 재시도할 수 있다")
    void outcome_commit_failure_repeats_safe_revocation() {
        var owner = apple();
        jdbc.execute("create trigger reject_deletion_update before update on account_deletions for each row signal sqlstate '45000' set message_text='synthetic failure'");
        try {
            assertThatThrownBy(() -> withdrawal.withdraw(owner.getId())).isInstanceOf(DataAccessException.class);
            assertThat(state(owner)).isEqualTo("PENDING");
            assertThat(count("apple_credentials", owner)).isOne();
        } finally { jdbc.execute("drop trigger reject_deletion_update"); }
        assertThat(withdrawal.withdraw(owner.getId())).isEqualTo(AccountWithdrawalService.Result.DELETED);
        verify(revocation, times(2)).revoke("synthetic-refresh");
    }

    @Test
    @DisplayName("HTTP 호출 중 프로세스 종료를 모사한 뒤 새 복구 작업이 DB 상태만으로 삭제를 이어간다")
    void recover_after_interrupted_external_call() {
        var owner = apple();
        when(revocation.revoke(anyString())).thenThrow(new AssertionError("simulated process termination"));
        assertThatThrownBy(() -> withdrawal.withdraw(owner.getId())).isInstanceOf(AssertionError.class);
        assertThat(state(owner)).isEqualTo("PENDING");
        doReturn(AppleTokenRevocation.Outcome.REVOKED).when(revocation).revoke(anyString());
        jdbc.update("update account_deletions set next_attempt_at=? where user_id=?", LocalDateTime.of(2000, 1, 1, 0, 0), owner.getId());
        new AccountDeletionRecovery(jdbc, withdrawal, jwtClock, true).recover();
        assertThat(users.existsById(owner.getId())).isFalse();
    }

    @Test
    @DisplayName("철회가 진행되는 동안 로그인과 Refresh 회전 및 Alarm 재활성화를 차단한다")
    void fence_login_refresh_and_mutations_during_http() throws Exception {
        var owner = apple();
        var alarm = alarm(owner);
        String token = tx().execute(status -> refresh.issue(owner));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(revocation.revoke(anyString())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return AppleTokenRevocation.Outcome.REVOKED;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deleting = executor.submit(() -> withdrawal.withdraw(owner.getId()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertDeletionInProgress(() -> login.loginApple(new ExternalIdentity(AuthProvider.APPLE, owner.getProviderUserId()),
                        ignored -> { throw new AssertionError("credential must not be replaced"); }));
                assertDeletionInProgress(() -> refresh.refresh(token));
                assertDeletionInProgress(() -> alarmService.activate(owner.getId(), alarm.getId()));
                assertDeletionInProgress(() -> alarmService.create(owner.getId(),
                        new com.stopbell.alarm.dto.CreateAlarmRequest(1L, false, false)));
                assertThat(jdbc.queryForObject("select status from alarms where id=?", String.class, alarm.getId())).isEqualTo("INACTIVE");
                assertThat(count("refresh_tokens", owner)).isOne();
            } finally { release.countDown(); }
            assertThat(deleting.get(5, TimeUnit.SECONDS)).isEqualTo(AccountWithdrawalService.Result.DELETED);
        }
    }

    @Test
    @DisplayName("삭제 전 Snapshot을 읽은 로그인이 User 잠금을 기다려도 삭제 상태를 current read로 검사한다")
    void stale_login_snapshot_cannot_issue_tokens() throws Exception {
        var owner = apple();
        var read = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var inHttp = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var loginThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        doAnswer(call -> {
            if (Thread.currentThread() == loginThread.get()) {
                read.countDown();
                assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call);
        }).when(users).findByIdForUpdate(owner.getId());
        when(revocation.revoke(anyString())).thenAnswer(call -> {
            inHttp.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            return AppleTokenRevocation.Outcome.REVOKED;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var loggingIn = executor.submit(() -> {
                loginThread.set(Thread.currentThread());
                return login.loginApple(new ExternalIdentity(AuthProvider.APPLE, owner.getProviderUserId()), ignored -> {});
            });
            assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            var deleting = executor.submit(() -> withdrawal.withdraw(owner.getId()));
            try {
                assertThat(inHttp.await(5, TimeUnit.SECONDS)).isTrue();
                resume.countDown();
                assertThatThrownBy(() -> loggingIn.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
                assertThat(count("refresh_tokens", owner)).isZero();
            } finally { resume.countDown(); finish.countDown(); }
            assertThat(deleting.get(5, TimeUnit.SECONDS)).isEqualTo(AccountWithdrawalService.Result.DELETED);
        } finally { doAnswer(call -> mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call))
                .when(users).findByIdForUpdate(owner.getId()); }
    }

    @Test
    @DisplayName("중복 탈퇴를 동시에 처리해도 사용자와 토큰이 한 번만 삭제된다")
    void concurrent_duplicate_requests_are_safe() throws Exception {
        var owner = apple();
        var both = new CountDownLatch(2);
        when(revocation.revoke(anyString())).thenAnswer(call -> {
            both.countDown(); assertThat(both.await(5, TimeUnit.SECONDS)).isTrue();
            return AppleTokenRevocation.Outcome.REVOKED;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> withdrawal.withdraw(owner.getId()));
            var second = executor.submit(() -> withdrawal.withdraw(owner.getId()));
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(AccountWithdrawalService.Result.DELETED);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(AccountWithdrawalService.Result.DELETED);
        }
        assertThat(withdrawal.withdraw(owner.getId())).isEqualTo(AccountWithdrawalService.Result.DELETED);
        assertThat(users.existsById(owner.getId())).isFalse();
    }

    @Test
    @DisplayName("User 잠금 충돌 중에는 철회를 시작하지 않고 잠금 해제 후 최신 상태로 진행한다")
    void wait_for_user_lock_before_revoking() throws Exception {
        var owner = apple();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<AccountWithdrawalService.Result>>();
            tx().executeWithoutResult(status -> {
                users.findByIdForUpdate(owner.getId()).orElseThrow();
                future.set(executor.submit(() -> withdrawal.withdraw(owner.getId())));
                assertThatThrownBy(() -> future.get().get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                verifyNoInteractions(revocation);
            });
            assertThat(future.get().get(5, TimeUnit.SECONDS)).isEqualTo(AccountWithdrawalService.Result.DELETED);
        }
    }

    @Test
    @DisplayName("외부 Transaction의 잠금을 HTTP 중 유지하지 않도록 잘못된 호출을 거부한다")
    void reject_ambient_transaction() {
        var owner = apple();
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> withdrawal.withdraw(owner.getId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("account_deletions", owner)).isZero();
        verifyNoInteractions(revocation);
    }

    @Test
    @DisplayName("일반 계정 삭제 실패도 NOT_REQUIRED에서 복구하며 외부 철회를 호출하지 않는다")
    void recover_non_apple_deletion_failure() {
        var owner = user(AuthProvider.GOOGLE);
        jdbc.execute("create trigger reject_google_delete before delete on users for each row signal sqlstate '45000' set message_text='synthetic failure'");
        try {
            assertThatThrownBy(() -> withdrawal.withdraw(owner.getId())).isInstanceOf(DataAccessException.class);
            assertThat(state(owner)).isEqualTo("NOT_REQUIRED");
        } finally { jdbc.execute("drop trigger reject_google_delete"); }
        assertThat(withdrawal.withdraw(owner.getId())).isEqualTo(AccountWithdrawalService.Result.DELETED);
        verifyNoInteractions(revocation);
    }

    @Test
    @DisplayName("늦은 불확실 철회 응답이 다른 요청의 삭제 완료를 PENDING으로 되돌리지 않는다")
    void late_uncertain_response_cannot_downgrade_completion() throws Exception {
        var owner = apple();
        var firstEntered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var finishSecond = new CountDownLatch(1);
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        when(revocation.revoke(anyString())).thenAnswer(call -> {
            if (sequence.incrementAndGet() == 1) {
                firstEntered.countDown();
                assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                return AppleTokenRevocation.Outcome.REVOKED;
            }
            secondEntered.countDown();
            assertThat(finishSecond.await(5, TimeUnit.SECONDS)).isTrue();
            return AppleTokenRevocation.Outcome.UNCERTAIN;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> withdrawal.withdraw(owner.getId()));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> withdrawal.withdraw(owner.getId()));
            try { assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(AccountWithdrawalService.Result.DELETED); }
            finally { finishSecond.countDown(); }
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(AccountWithdrawalService.Result.DELETED);
        }
        assertThat(count("account_deletions", owner)).isZero();
    }

    @Test
    @DisplayName("철회 대기 중 Source와 발송을 종료하고 최종 삭제 시 타 사용자 Event와 참조 분리 정책을 유지한다")
    void stop_sources_and_preserve_other_notifications() {
        var owner = apple();
        var other = user(AuthProvider.GOOGLE);
        var ownAlarm = alarm(owner);
        var otherAlarm = alarm(other);
        var now = LocalDateTime.ofInstant(jwtClock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        var device = devices.saveAndFlush(new Device(owner, UUID.randomUUID().toString(),
                "a".repeat(64), DevicePlatform.IOS, UUID.randomUUID().toString(), 0, now));
        var evidence = new TransitEvent(TransitEventType.ONE_STOP_BEFORE,
                "vehicle", UUID.randomUUID(), now.toInstant(ZoneOffset.UTC), null, null, null, null, null, null, null);
        var ownEvent = events.saveAndFlush(new NotificationEvent(
                ownAlarm.getId(), owner, ownAlarm.getActivationGeneration(), evidence, now, now));
        var otherEvent = events.saveAndFlush(new NotificationEvent(
                otherAlarm.getId(), other, otherAlarm.getActivationGeneration(), evidence, now, now));
        var ownDelivery = deliveries.saveAndFlush(new NotificationDelivery(
                ownEvent, device, 0, now.plusMinutes(5), now));
        var otherDelivery = deliveries.saveAndFlush(new NotificationDelivery(
                otherEvent, device, 0, now.plusMinutes(5), now));
        var traversal = new BoardingTraversalSnapshot(1, TransitProvider.SEOUL_BUS,
                "route", null, List.of(
                new BoardingTraversalSnapshot.Occurrence(1, "s0", 1),
                new BoardingTraversalSnapshot.Occurrence(2, "s1", 2),
                new BoardingTraversalSnapshot.Occurrence(3, "s2", 3)),
                2, BoardingTraversalSnapshot.AlightAvailability.ALLOWED, "controlled-test-evidence");
        var checkpoint = new BoardingCheckpoint("s0", 1, 0,
                BoardingCheckpoint.Evidence.ID_ORDER, null, now, now);
        var session = sessions.saveAndFlush(new BoardingSession(owner, UUID.randomUUID().toString(),
                "a".repeat(64), "vehicle", "number", "route", "target", traversal, checkpoint, now, now.plusHours(4)));
        jdbc.update("insert into alarm_creation_requests (user_id, client_request_id, request_fingerprint, alarm_id, created_at, expires_at) values (?,?,?,?,?,?)",
                owner.getId(), UUID.randomUUID().toString(), new byte[32], ownAlarm.getId(), now, now.plusHours(1));
        when(revocation.revoke(anyString())).thenReturn(AppleTokenRevocation.Outcome.UNCERTAIN);
        assertThat(withdrawal.withdraw(owner.getId())).isEqualTo(AccountWithdrawalService.Result.PENDING);
        assertThat(sessions.findById(session.getId()).orElseThrow().getStatus())
                .isEqualTo(BoardingSessionStatus.CANCELLED);
        assertThat(deliveries.findById(ownDelivery.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(jdbc.queryForObject("select status from alarms where id=?", String.class, ownAlarm.getId())).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("select status from notification_deliveries where id=?", String.class, otherDelivery.getId())).isEqualTo("PENDING");
        when(revocation.revoke(anyString())).thenReturn(AppleTokenRevocation.Outcome.REVOKED);
        assertThat(withdrawal.withdraw(owner.getId())).isEqualTo(AccountWithdrawalService.Result.DELETED);
        assertThat(sessions.existsById(session.getId())).isFalse();
        assertThat(events.existsById(ownEvent.getId())).isFalse();
        assertThat(deliveries.existsById(ownDelivery.getId())).isFalse();
        assertThat(devices.existsById(device.getId())).isFalse();
        assertThat(count("alarm_creation_requests", owner)).isZero();
        assertThat(users.existsById(other.getId())).isTrue();
        assertThat(events.existsById(otherEvent.getId())).isTrue();
        assertThat(alarms.existsById(otherAlarm.getId())).isTrue();
        assertThat(jdbc.queryForObject("select device_id from notification_deliveries where id=?", Long.class, otherDelivery.getId())).isNull();
        assertThat(jdbc.queryForObject("select status from notification_deliveries where id=?", String.class, otherDelivery.getId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("삭제 시작 저장 실패는 진행 상태와 Source 변경을 남기지 않고 철회도 호출하지 않는다")
    void preparation_failure_rolls_back_intent_and_sources() {
        var owner = apple();
        var alarm = alarm(owner);
        jdbc.execute("create trigger reject_deletion_insert before insert on account_deletions for each row signal sqlstate '45000' set message_text='synthetic failure'");
        try {
            assertThatThrownBy(() -> withdrawal.withdraw(owner.getId())).isInstanceOf(DataAccessException.class);
            assertThat(count("account_deletions", owner)).isZero();
            assertThat(count("apple_credentials", owner)).isOne();
            assertThat(jdbc.queryForObject("select status from alarms where id=?", String.class, alarm.getId())).isEqualTo("ACTIVE");
            verifyNoInteractions(revocation);
        } finally { jdbc.execute("drop trigger reject_deletion_insert"); }
        assertThat(withdrawal.withdraw(owner.getId())).isEqualTo(AccountWithdrawalService.Result.DELETED);
    }

    @Test
    @DisplayName("실제 MySQL 잠금 timeout은 철회 전 rollback되며 잠금 해제 후 다시 삭제할 수 있다")
    void database_lock_timeout_before_http_is_recoverable() throws Exception {
        var owner = apple();
        var deletionThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        doAnswer(call -> {
            if (Thread.currentThread() != deletionThread.get()) {
                return mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call);
            }
            jdbc.execute("set session innodb_lock_wait_timeout=1");
            try { return mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call); }
            finally { jdbc.execute("set session innodb_lock_wait_timeout=50"); }
        }).when(users).findByIdForUpdate(owner.getId());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            tx().executeWithoutResult(status -> {
                users.findByIdForUpdate(owner.getId()).orElseThrow();
                var attempt = executor.submit(() -> {
                    deletionThread.set(Thread.currentThread());
                    return withdrawal.withdraw(owner.getId());
                });
                assertThatThrownBy(() -> attempt.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(DataAccessException.class);
                verifyNoInteractions(revocation);
                assertThat(count("account_deletions", owner)).isZero();
            });
        } finally {
            doAnswer(call -> mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call))
                    .when(users).findByIdForUpdate(owner.getId());
        }
        assertThat(withdrawal.withdraw(owner.getId())).isEqualTo(AccountWithdrawalService.Result.DELETED);
    }

    private User user(AuthProvider provider) { return users.saveAndFlush(new User(provider, UUID.randomUUID().toString())); }
    private User apple() {
        var user = user(AuthProvider.APPLE);
        jdbc.update("insert into apple_credentials (user_id, encrypted_refresh_token) values (?,?)", user.getId(), cipher.encrypt(user.getId(), "synthetic-refresh"));
        return user;
    }
    private Alarm alarm(User user) {
        var alarm = new Alarm(user, new BusAlarmTarget(TransitProvider.SEOUL_BUS, "route", "stop", 3,
                "route", "stop", null, null, null, null, null));
        alarm.activate();
        return alarms.saveAndFlush(alarm);
    }
    private String state(User user) { return jdbc.queryForObject("select state from account_deletions where user_id=?", String.class, user.getId()); }
    private int count(String table, User user) { return jdbc.queryForObject("select count(*) from " + table + " where user_id=?", Integer.class, user.getId()); }
    private TransactionTemplate tx() { return new TransactionTemplate(manager); }
    private void assertManual(User owner) throws Exception {
        mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer " + tokens.createAccessToken(owner.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("DELETED"))
                .andExpect(jsonPath("appleRevocation").value("MANUAL_REVOCATION_REQUIRED"));
        assertThat(users.existsById(owner.getId())).isFalse();
    }
    private void assertDeletionInProgress(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS));
    }
}
