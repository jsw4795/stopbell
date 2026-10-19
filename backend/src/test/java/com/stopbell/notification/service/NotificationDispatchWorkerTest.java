package com.stopbell.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.stopbell.notification.client.FcmProviderClient;
import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.notification.entity.NotificationProviderResult;
import com.stopbell.notification.entity.Device;
import com.stopbell.notification.entity.NotificationDelivery;
import com.stopbell.notification.entity.NotificationEvent;
import com.stopbell.notification.entity.NotificationSourceType;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationDispatchWorkerTest {
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private final NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
    private final NotificationDispatchAttemptService attempts = mock(NotificationDispatchAttemptService.class);
    private final FcmProviderClient provider = mock(FcmProviderClient.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private NotificationDispatchWorker worker;

    @BeforeEach
    void set_up() {
        worker = worker(true);
        when(deliveries.findDuePendingIds(any(), any())).thenReturn(List.of(11L, 12L));
        when(attempts.beginAttempt(anyLong())).thenAnswer(call -> Optional.of(snapshot(call.getArgument(0), NOW.plusSeconds(60))));
        when(provider.send(any())).thenReturn(result(NotificationProviderResult.ACCEPTED));
    }

    @Test
    @DisplayName("비활성 설정과 shutdown은 후보 조회 및 전송을 시작하지 않는다")
    void disabled_and_shutdown_do_not_dispatch() {
        worker(false).dispatchPending();
        worker.stop();
        worker.dispatchPending();
        verifyNoInteractions(deliveries, attempts, provider);
    }

    @Test
    @DisplayName("UTC 시각과 bounded Pageable로 조회하고 commit된 실제 Event hint를 한 번씩 전달한다")
    void bounded_query_and_exact_snapshot_results() {
        worker.dispatchPending();
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(deliveries).findDuePendingIds(eq(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)), page.capture());
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(2);
        var ordered = inOrder(attempts, provider);
        for (long id : List.of(11L, 12L)) {
            NotificationAttemptSnapshot snapshot = snapshot(id, NOW.plusSeconds(60));
            ordered.verify(attempts).beginAttempt(id);
            ordered.verify(provider).send(snapshot.target().request());
            ordered.verify(attempts).completeAttempt(snapshot, result(NotificationProviderResult.ACCEPTED));
        }
        verify(provider, times(2)).send(any());
        assertThat(snapshot(11L, NOW.plusSeconds(60)).toString()).doesNotContain("synthetic-fid");
        assertThat(snapshot(11L, NOW.plusSeconds(60)).target().toString()).doesNotContain("synthetic-fid");
    }

    @Test
    @DisplayName("동일 Worker의 동시 호출은 첫 cycle이 끝날 때까지 차단된다")
    void overlapping_cycle_is_rejected() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(deliveries.findDuePendingIds(any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        Thread first = Thread.startVirtualThread(worker::dispatchPending);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.dispatchPending();
            verify(deliveries, times(1)).findDuePendingIds(any(), any());
        } finally {
            release.countDown();
            first.join(5_000);
        }
        assertThat(first.isAlive()).isFalse();
        worker.dispatchPending();
        verify(deliveries, times(2)).findDuePendingIds(any(), any());
    }

    @Test
    @DisplayName("미확정 commit이나 시작 실패 뒤 Provider를 호출하지 않고 DB 장애 cycle을 종료한다")
    void unconfirmed_commit_blocks_send() {
        when(attempts.beginAttempt(11L)).thenThrow(new TransactionSystemException("synthetic commit failure"));
        worker.dispatchPending();
        verifyNoInteractions(provider);
        verify(attempts, never()).beginAttempt(12L);
        reset(attempts);
        when(attempts.beginAttempt(anyLong())).thenReturn(Optional.empty());
        worker.dispatchPending();
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("commit 후 호출 직전 기한과 같거나 늦으면 Provider 없이 동일 attempt의 expiry 처리를 위임한다")
    void expiry_at_send_boundary() {
        doAnswer(call -> Optional.of(snapshot(call.getArgument(0), NOW))).when(attempts).beginAttempt(anyLong());
        worker.dispatchPending();
        verifyNoInteractions(provider);
        for (long id : List.of(11L, 12L)) {
            verify(attempts).expireBeforeSend(snapshot(id, NOW));
        }
    }

    @Test
    @DisplayName("여섯 Provider result를 재분류하지 않고 동일 snapshot의 handler에 전달한다")
    void forwards_all_normalized_results() {
        when(deliveries.findDuePendingIds(any(), any())).thenReturn(List.of(11L));
        for (NotificationProviderResult kind : NotificationProviderResult.values()) {
            NotificationSendResult result = result(kind);
            when(provider.send(any())).thenReturn(result);
            worker = worker(true);
            worker.dispatchPending();
            verify(attempts).completeAttempt(snapshot(11L, NOW.plusSeconds(60)), result);
        }
        verify(provider, times(6)).send(any());
    }

    @Test
    @DisplayName("한 후보의 일반 오류는 다음 후보를 막지 않고 결과 저장 실패는 즉시 재전송하지 않는다")
    void isolates_candidate_failure_without_resend() {
        when(attempts.beginAttempt(11L)).thenThrow(new IllegalStateException("synthetic failure"));
        worker.dispatchPending();
        verify(provider, times(1)).send(snapshot(12L, NOW.plusSeconds(60)).target().request());
        reset(attempts, provider);
        when(attempts.beginAttempt(anyLong())).thenAnswer(call -> Optional.of(snapshot(call.getArgument(0), NOW.plusSeconds(60))));
        when(provider.send(any())).thenReturn(result(NotificationProviderResult.ACCEPTED));
        doThrow(new DataAccessResourceFailureException("synthetic DB outage"))
                .when(attempts).completeAttempt(any(), any());
        worker.dispatchPending();
        verify(provider, times(1)).send(any());
        verify(attempts, never()).beginAttempt(12L);
    }

    @Test
    @DisplayName("호출자를 감싼 DB transaction 또는 synchronization이 있으면 dispatch를 차단한다")
    void enclosing_transaction_is_rejected() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            worker.dispatchPending();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        TransactionSynchronizationManager.initSynchronization();
        try {
            worker.dispatchPending();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verifyNoInteractions(deliveries, attempts, provider);
        worker.dispatchPending();
        verify(provider, times(2)).send(any());
    }

    @Test
    @DisplayName("Schema 밖의 잘못된 recipient에도 null 또는 유효하지 않은 FID를 전송하지 않는다")
    void invalid_current_target_is_rejected() {
        DeviceRepository devices = mock(DeviceRepository.class);
        Device device = mock(Device.class);
        NotificationDelivery delivery = mock(NotificationDelivery.class);
        NotificationEvent event = mock(NotificationEvent.class);
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(1L);
        when(device.getId()).thenReturn(2L);
        when(device.getUser()).thenReturn(owner);
        when(device.isEnabled()).thenReturn(true);
        when(delivery.getDevice()).thenReturn(device);
        when(delivery.getNotificationEvent()).thenReturn(event);
        when(event.getSourceType()).thenReturn(NotificationSourceType.ALARM);
        when(event.getUser()).thenReturn(owner);
        when(devices.findByIdForUpdate(2L)).thenReturn(Optional.of(device));
        NotificationRecipientPreflight preflight = new NotificationRecipientPreflight(devices);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            for (String fid : new String[] {null, "", " ", "bad fid"}) {
                when(device.getCurrentPushTargetId()).thenReturn(fid);
                assertThat(preflight.check(delivery).rejection()).isEqualTo(NotificationRecipientPreflight.Rejection.TARGET_MISSING);
            }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("한 Provider 호출의 예외는 재전송이나 결과 조작 없이 격리하고 민감한 원문을 로그에 남기지 않는다")
    void provider_exception_isolated_and_redacted(CapturedOutput output) {
        when(provider.send(any()))
                .thenThrow(new IllegalStateException("raw-firebase-body synthetic-fid synthetic-credential"))
                .thenReturn(result(NotificationProviderResult.ACCEPTED));
        worker.dispatchPending();
        verify(provider, times(2)).send(any());
        verify(attempts, never()).completeAttempt(eq(snapshot(11L, NOW.plusSeconds(60))), any());
        verify(attempts).completeAttempt(snapshot(12L, NOW.plusSeconds(60)), result(NotificationProviderResult.ACCEPTED));
        assertThat(output).contains("exceptionType=IllegalStateException")
                .doesNotContain("raw-firebase-body", "synthetic-fid", "synthetic-credential");
    }

    private NotificationDispatchWorker worker(boolean enabled) {
        return new NotificationDispatchWorker(new NotificationDispatchProperties(enabled, 2, Duration.ofSeconds(1)),
                deliveries, attempts, provider, clock);
    }

    private NotificationAttemptSnapshot snapshot(long id, Instant expiresAt) {
        return new NotificationAttemptSnapshot(new NotificationDispatchTarget(id, 81, 71, TransitEventType.ARRIVED,
                61, 51, 3, 4, "synthetic-fid", LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)),
                2, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private NotificationSendResult result(NotificationProviderResult kind) {
        return new NotificationSendResult(kind, null, null, NOW, 1, true);
    }
}
