package com.stopbell.notification.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.notification.entity.*;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DurableNotificationAttemptHandlerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 0, 0);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final Clock clock = mock(Clock.class);
    private DurableNotificationAttemptHandler handler;
    private NotificationDelivery delivery;
    private NotificationDispatchTarget target;

    @BeforeEach
    void set_up() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(clock.instant()).thenReturn(NOW.toInstant(ZoneOffset.UTC));
        handler = new DurableNotificationAttemptHandler(new NotificationRetryPolicy(new NotificationRetryProperties(
                3, Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ZERO, Duration.ofSeconds(10)),
                Duration.ofSeconds(15), bound -> 0), devices, clock);
        NotificationEvent event = mock(NotificationEvent.class);
        when(event.getCreatedAt()).thenReturn(NOW);
        when(event.getObservedAt()).thenReturn(NOW.minusSeconds(1));
        delivery = new NotificationDelivery(event, mock(Device.class), 0, NOW.plusSeconds(60), NOW);
        ReflectionTestUtils.setField(delivery, "id", 1L);
        target = new NotificationDispatchTarget(1, 2, 3, TransitEventType.ARRIVED, 4, 5, 0, 7,
                "synthetic-fid", delivery.getExpiresAt());
    }

    @AfterEach
    void tear_down() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @ParameterizedTest
    @EnumSource(NotificationProviderResult.class)
    @DisplayName("여섯 Provider 결과의 첫 attempt 상태와 code 및 count를 정확히 기록한다")
    void first_attempt_result_transition(NotificationProviderResult kind) {
        NotificationAttemptSnapshot snapshot = handler.beginAttempt(delivery, target, NOW);
        handler.completeAttempt(delivery, snapshot, result(kind, NOW));
        NotificationDeliveryStatus expected = switch (kind) {
            case ACCEPTED -> NotificationDeliveryStatus.ACCEPTED;
            case RETRYABLE, AMBIGUOUS_TIMEOUT -> NotificationDeliveryStatus.PENDING;
            default -> NotificationDeliveryStatus.FAILED;
        };
        assertThat(delivery.getStatus()).isEqualTo(expected);
        assertThat(delivery.getLastProviderResult()).isEqualTo(kind);
        assertThat(delivery.getAttemptCount()).isOne();
        if (expected == NotificationDeliveryStatus.PENDING) {
            assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        } else {
            assertThat(delivery.getNextAttemptAt()).isNull();
        }
        if (kind == NotificationProviderResult.ACCEPTED) {
            assertThat(delivery.getLastFailureCode()).isNull();
            assertThat(delivery.getProviderAcceptedAt()).isEqualTo(NOW);
        }
    }

    @ParameterizedTest
    @EnumSource(NotificationProviderResult.class)
    @DisplayName("마지막 attempt budget이 소진돼도 명확한 접수는 ACCEPTED이고 다른 결과는 FAILED이다")
    void final_attempt_result_transition(NotificationProviderResult kind) {
        ReflectionTestUtils.setField(delivery, "attemptCount", 2);
        NotificationAttemptSnapshot snapshot = handler.beginAttempt(delivery, target, NOW);
        handler.completeAttempt(delivery, snapshot, result(kind, NOW));
        assertThat(delivery.getStatus()).isEqualTo(kind == NotificationProviderResult.ACCEPTED
                ? NotificationDeliveryStatus.ACCEPTED : NotificationDeliveryStatus.FAILED);
        assertThat(delivery.getAttemptCount()).isEqualTo(3);
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getLastProviderResult()).isEqualTo(kind);
    }

    @ParameterizedTest
    @EnumSource(NotificationProviderResult.class)
    @DisplayName("결과 반영 시 기한이 지났으면 EXPIRED이나 확인된 ACCEPTED는 접수 시각 그대로 보존한다")
    void expired_result_transition(NotificationProviderResult kind) {
        NotificationAttemptSnapshot snapshot = handler.beginAttempt(delivery, target, NOW);
        LocalDateTime received = NOW.plusSeconds(60);
        when(clock.instant()).thenReturn(received.toInstant(ZoneOffset.UTC));
        handler.completeAttempt(delivery, snapshot, result(kind, received));
        assertThat(delivery.getStatus()).isEqualTo(kind == NotificationProviderResult.ACCEPTED
                ? NotificationDeliveryStatus.ACCEPTED : NotificationDeliveryStatus.EXPIRED);
        assertThat(delivery.getLastProviderResult()).isEqualTo(kind);
        assertThat(delivery.getAttemptCount()).isOne();
        assertThat(delivery.getExpiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(NotificationRecipientPreflight.Rejection.class)
    @DisplayName("모든 recipient 거부는 count를 소비하지 않고 Provider 결과 없이 local terminal이 된다")
    void recipient_rejection_is_local(NotificationRecipientPreflight.Rejection reason) {
        handler.rejectRecipient(delivery, reason, NOW);
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(delivery.getAttemptCount()).isZero();
        assertThat(delivery.getLastProviderResult()).isNull();
        assertThat(delivery.getLastFailureCode()).isEqualTo(reason == NotificationRecipientPreflight.Rejection.DEVICE_MISSING
                ? "DISPATCH_NOT_ALLOWED" : "RECIPIENT_" + reason.name());
        verifyNoInteractions(devices);
    }

    @Test
    @DisplayName("expiry를 budget 및 recipient보다 먼저 적용하고 local 종료에서는 이전 실제 결과를 보존한다")
    void expiry_precedes_budget_and_recipient() {
        ReflectionTestUtils.setField(delivery, "attemptCount", 3);
        ReflectionTestUtils.setField(delivery, "lastProviderResult", NotificationProviderResult.RETRYABLE);
        assertThat(handler.checkEligibility(delivery, NOW.plusSeconds(60))).isFalse();
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(delivery.getLastProviderResult()).isEqualTo(NotificationProviderResult.RETRYABLE);
        assertThat(delivery.getLastFailureCode()).isEqualTo("FRESHNESS_EXPIRED");
        handler.rejectRecipient(delivery, NotificationRecipientPreflight.Rejection.OWNER_CHANGED, NOW.plusSeconds(60));
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
    }

    @Test
    @DisplayName("시작마다 count와 revision을 영속하고 이전 결과를 지우며 recovery 예약을 고정한다")
    void attempt_start_clears_previous_result_and_reserves_recovery() {
        handler.beginAttempt(delivery, target, NOW);
        handler.completeAttempt(delivery, new NotificationAttemptSnapshot(target, 1, NOW), result(NotificationProviderResult.RETRYABLE, NOW));
        NotificationAttemptSnapshot second = handler.beginAttempt(delivery, target, NOW.plusSeconds(10));
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(second.lastAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(delivery.getLastAttemptRegistrationRevision()).isEqualTo(7);
        assertThat(delivery.getLastProviderResult()).isNull();
        assertThat(delivery.getLastFailureCode()).isNull();
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(35));
        assertThat(delivery.getExpiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("TTL보다 긴 retry는 PENDING으로 기한에 예약하고 그 시각에만 EXPIRED로 전이한다")
    void retry_waits_for_actual_expiry() {
        NotificationAttemptSnapshot snapshot = handler.beginAttempt(delivery, target, NOW);
        handler.completeAttempt(delivery, snapshot, new NotificationSendResult(NotificationProviderResult.RETRYABLE,
                "UNAVAILABLE", Duration.ofSeconds(Long.MAX_VALUE), NOW.toInstant(ZoneOffset.UTC), 1, true));
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(delivery.getExpiresAt());
        assertThat(handler.checkEligibility(delivery, NOW.plusSeconds(59))).isFalse();
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(handler.checkEligibility(delivery, NOW.plusSeconds(60))).isFalse();
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
    }

    @Test
    @DisplayName("budget 소진과 호출 직전 expiry는 가짜 Provider 결과나 추가 attempt를 만들지 않는다")
    void local_budget_and_send_expiry_keep_accounting() {
        NotificationAttemptSnapshot snapshot = handler.beginAttempt(delivery, target, NOW);
        handler.expireBeforeSend(delivery, snapshot, NOW.plusSeconds(60));
        assertThat(delivery.getAttemptCount()).isOne();
        assertThat(delivery.getLastProviderResult()).isNull();
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(handler.checkEligibility(delivery, NOW.plusSeconds(61))).isFalse();
        assertThatThrownBy(() -> handler.beginAttempt(delivery, target, NOW.plusSeconds(61)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("명확한 UNREGISTERED에서만 Device cleanup을 조회하고 임의 code는 안전한 값으로 제한한다")
    void cleanup_evidence_and_codes_are_restricted() {
        NotificationAttemptSnapshot snapshot = handler.beginAttempt(delivery, target, NOW);
        handler.completeAttempt(delivery, snapshot, new NotificationSendResult(NotificationProviderResult.INVALID_TARGET,
                "raw-body-synthetic-fid", Duration.ZERO, NOW.toInstant(ZoneOffset.UTC), 1, true));
        assertThat(delivery.getLastFailureCode()).isEqualTo("UNKNOWN_ACCEPTANCE");
        verifyNoInteractions(devices);
    }

    @Test
    @DisplayName("등록 해제 cleanup은 모든 current 필드가 일치하는 경우에만 수행한다")
    void cleanup_matches_current_registration() {
        Device device = mock(Device.class);
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(5L);
        when(device.getId()).thenReturn(4L);
        when(device.getUser()).thenReturn(owner);
        when(device.isEnabled()).thenReturn(true);
        when(device.getRegistrationRevision()).thenReturn(7L);
        when(device.getCurrentPushTargetId()).thenReturn("synthetic-fid");
        when(devices.findByIdForUpdate(4L)).thenReturn(Optional.of(device));
        NotificationAttemptSnapshot snapshot = handler.beginAttempt(delivery, target, NOW);
        handler.completeAttempt(delivery, snapshot, result(NotificationProviderResult.INVALID_TARGET, NOW));
        verify(device).disableUnregisteredTarget();
    }

    @Test
    @DisplayName("handler는 caller transaction 없이 실행할 수 없다")
    void requires_caller_transaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> handler.checkEligibility(delivery, NOW)).isInstanceOf(IllegalStateException.class);
    }

    private NotificationSendResult result(NotificationProviderResult kind, LocalDateTime received) {
        String code = switch (kind) {
            case ACCEPTED -> null;
            case INVALID_TARGET -> "UNREGISTERED";
            case RETRYABLE -> "UNAVAILABLE";
            case CONFIGURATION -> "AUTH_OR_PROJECT_ERROR";
            case PERMANENT_REQUEST -> "INVALID_ARGUMENT_UNCLASSIFIED";
            case AMBIGUOUS_TIMEOUT -> "UNKNOWN_ACCEPTANCE";
        };
        return new NotificationSendResult(kind, code, Duration.ofSeconds(10), received.toInstant(ZoneOffset.UTC), 1, true);
    }
}
