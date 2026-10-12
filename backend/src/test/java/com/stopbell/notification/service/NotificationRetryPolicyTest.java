package com.stopbell.notification.service;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.notification.entity.NotificationProviderResult;
import com.stopbell.transit.domain.TransitEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationRetryPolicyTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 0, 0);
    private final NotificationRetryProperties settings = settings(Duration.ofSeconds(2));

    @Test
    @DisplayName("최초 포함 count에 따라 exponential backoff를 증가시키고 cap과 양수 jitter를 지킨다")
    void exponential_delay_and_jitter_are_bounded() {
        NotificationRetryPolicy minimum = policy(bound -> 0);
        NotificationRetryPolicy maximum = policy(bound -> bound - 1);
        assertThat(List.of(minimum.localDelay(1), minimum.localDelay(2), minimum.localDelay(3),
                minimum.localDelay(4), minimum.localDelay(Integer.MAX_VALUE)))
                .containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(40),
                        Duration.ofSeconds(60), Duration.ofSeconds(60));
        assertThat(maximum.localDelay(1)).isEqualTo(Duration.ofSeconds(12));
        assertThat(maximum.localDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(60));
        assertThatThrownBy(() -> minimum.localDelay(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("일반 및 ambiguous는 최소 10초이며 quota 60초와 Provider Retry-After를 cap보다 우선한다")
    void provider_floor_and_retry_after_are_preserved() {
        NotificationRetryPolicy policy = policy(bound -> 0);
        assertThat(policy.retryAt(1, result(NotificationProviderResult.RETRYABLE, "INTERNAL", Duration.ZERO), NOW.plusMinutes(5)))
                .isEqualTo(NOW.plusSeconds(10));
        assertThat(policy.retryAt(1, result(NotificationProviderResult.AMBIGUOUS_TIMEOUT, "UNKNOWN_ACCEPTANCE", Duration.ZERO), NOW.plusMinutes(5)))
                .isEqualTo(NOW.plusSeconds(10));
        assertThat(policy.retryAt(1, result(NotificationProviderResult.RETRYABLE, "QUOTA_EXCEEDED", Duration.ZERO), NOW.plusMinutes(5)))
                .isEqualTo(NOW.plusSeconds(60));
        assertThat(policy.retryAt(1, result(NotificationProviderResult.RETRYABLE, "UNAVAILABLE", Duration.ofSeconds(90)), NOW.plusMinutes(5)))
                .isEqualTo(NOW.plusSeconds(90));
    }

    @Test
    @DisplayName("기한 이상 또는 overflow Retry-After는 실제 만료 시각을 예약하고 하한을 줄이지 않는다")
    void deadline_and_overflow_are_safe() {
        NotificationRetryPolicy policy = policy(bound -> 0);
        for (Duration delay : List.of(Duration.ofSeconds(60), Duration.ofSeconds(90), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThat(policy.retryAt(1, result(NotificationProviderResult.RETRYABLE, "UNAVAILABLE", delay), NOW.plusSeconds(60)))
                    .isEqualTo(NOW.plusSeconds(60));
        }
        NotificationSendResult fractional = new NotificationSendResult(NotificationProviderResult.RETRYABLE,
                "UNAVAILABLE", Duration.ofSeconds(10).plusNanos(1), NOW.toInstant(ZoneOffset.UTC).plusNanos(1), 1, true);
        assertThat(policy.retryAt(1, fractional, NOW.plusSeconds(60)))
                .isEqualTo(NOW.plusSeconds(10).plusNanos(1_000));
    }

    @Test
    @DisplayName("recovery는 동일 FCM request budget과 최소 지연을 사용하며 기한을 넘기지 않는다")
    void recovery_uses_provider_budget() {
        assertThat(policy(bound -> 0).recoveryAt(NOW, NOW.plusSeconds(60))).isEqualTo(NOW.plusSeconds(25));
        assertThat(policy(bound -> 0).recoveryAt(NOW, NOW.plusSeconds(20))).isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    @DisplayName("retry와 request budget의 무한에 가까운 값 및 유효하지 않은 범위를 거부한다")
    void invalid_settings_are_rejected() {
        for (int count : new int[] {0, -1, 11, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> new NotificationRetryProperties(count, Duration.ofSeconds(10), Duration.ofSeconds(60),
                    Duration.ZERO, Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
        }
        for (Duration base : List.of(Duration.ZERO, Duration.ofSeconds(9), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> new NotificationRetryProperties(3, base, Duration.ofSeconds(60),
                    Duration.ZERO, Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> settings(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(Duration.ofSeconds(61))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationRetryProperties(3, Duration.ofSeconds(20), Duration.ofSeconds(10),
                Duration.ZERO, Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationRetryProperties(3, Duration.ofSeconds(10), Duration.ofSeconds(60),
                Duration.ZERO, Duration.ofSeconds(9))).isInstanceOf(IllegalArgumentException.class);
        for (Duration budget : List.of(Duration.ZERO, Duration.ofMinutes(6), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> new NotificationRetryPolicy(settings, budget, bound -> 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Type별 TTL 기본 후보는 60초이고 양수 microsecond 정밀도와 5분 상한을 검증한다")
    void ttl_remains_provisional_and_bounded() {
        Duration ttl = Duration.ofSeconds(60);
        NotificationDecisionTtlPolicy policy = new NotificationDecisionTtlPolicy(ttl, ttl, ttl, ttl);
        for (TransitEventType type : TransitEventType.values()) {
            assertThat(policy.expiresAt(type, Instant.EPOCH)).isEqualTo(Instant.EPOCH.plusSeconds(60));
        }
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofMinutes(6), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> new NotificationDecisionTtlPolicy(invalid, ttl, ttl, ttl))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private NotificationRetryPolicy policy(java.util.function.LongUnaryOperator jitter) {
        return new NotificationRetryPolicy(settings, Duration.ofSeconds(15), jitter);
    }

    private NotificationRetryProperties settings(Duration jitter) {
        return new NotificationRetryProperties(3, Duration.ofSeconds(10), Duration.ofSeconds(60), jitter, Duration.ofSeconds(10));
    }

    private NotificationSendResult result(NotificationProviderResult kind, String code, Duration delay) {
        return new NotificationSendResult(kind, code, delay, NOW.toInstant(ZoneOffset.UTC), 1, true);
    }
}
