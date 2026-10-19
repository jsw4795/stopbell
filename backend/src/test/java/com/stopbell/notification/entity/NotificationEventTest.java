package com.stopbell.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import com.stopbell.boarding.entity.BoardingCheckpoint;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class NotificationEventTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 0, 0);
    private final User user = new User(AuthProvider.GOOGLE, "fixture-owner");

    @ParameterizedTest
    @EnumSource(TransitEventType.class)
    @DisplayName("기존 Alarm 생성자는 네 TransitEventType과 identity를 보존한다")
    void preserve_alarm_constructor(TransitEventType type) {
        UUID cycle = UUID.randomUUID();
        NotificationEvent event = new NotificationEvent(1L, user, 2,
                new TransitEvent(type, "vehicle", cycle, NOW.toInstant(ZoneOffset.UTC),
                        null, null, null, null, null, null, null), NOW, NOW);
        assertThat(event.getSourceType()).isEqualTo(NotificationSourceType.ALARM);
        assertThat(event.getAlarmId()).isEqualTo(1L);
        assertThat(event.getActivationGeneration()).isEqualTo(2L);
        assertThat(event.getTrackingCycleId()).isEqualTo(cycle);
        assertThat(event.getEventType()).isEqualTo(type);
        assertThat(event.getBoardingSessionId()).isNull();
        assertThat(event.getBoardingEventType()).isNull();
        assertThat(event.getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("Session Event는 Alarm identity 없이 검증된 근거와 UTC microsecond 시각을 보존한다")
    void preserve_session_evidence_and_microseconds() {
        LocalDateTime precise = NOW.plusNanos(123456789);
        BoardingCheckpoint checkpoint = new BoardingCheckpoint("stop", 20, 1, BoardingCheckpoint.Evidence.ID_ORDER,
                null, precise, precise.minusSeconds(1));
        NotificationEvent event = session(1L, user, BoardingNotificationEventType.PREPARE_ALIGHT,
                checkpoint, precise, precise, precise.plusSeconds(30));
        assertThat(event.getSourceType()).isEqualTo(NotificationSourceType.BOARDING_SESSION);
        assertThat(event.getBoardingSessionId()).isEqualTo(1L);
        assertThat(event.getBoardingEventType()).isEqualTo(BoardingNotificationEventType.PREPARE_ALIGHT);
        assertThat(event.getUser()).isSameAs(user);
        assertThat(event.getAlarmId()).isNull();
        assertThat(event.getActivationGeneration()).isNull();
        assertThat(event.getTrackingCycleId()).isNull();
        assertThat(event.getEventType()).isNull();
        assertThat(event.getObservedAt()).isEqualTo(NOW.plusNanos(123456000));
        assertThat(event.getEventDetectedAt()).isEqualTo(event.getObservedAt());
        assertThat(event.getCreatedAt()).isEqualTo(event.getObservedAt());
        assertThat(event.getExpiresAt()).isEqualTo(event.getObservedAt().plusSeconds(30));
        assertThat(event.getProviderDataTime()).isEqualTo(event.getObservedAt().minusSeconds(1));
        assertThat(event.getCurrentStopExternalId()).isEqualTo("stop");
        assertThat(event.getCurrentStopOrder()).isEqualTo(20);
        assertThat(event.getCurrentStopName()).isNull();
        assertThat(event.getLatitude()).isNull();
        assertThat(event.getLongitude()).isNull();
        assertThat(event.getStopsPastTarget()).isNull();
    }

    @Test
    @DisplayName("검증된 order-only 근거는 존재하지 않는 Stop ID나 Provider 시각을 합성하지 않는다")
    void preserve_order_only_evidence() {
        NotificationEvent event = session(1L, user, BoardingNotificationEventType.PREPARE_ALIGHT,
                checkpoint(), NOW, NOW, NOW.plusSeconds(30));
        assertThat(event.getCurrentStopExternalId()).isNull();
        assertThat(event.getCurrentStopOrder()).isEqualTo(20);
        assertThat(event.getProviderDataTime()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalid_inputs")
    @DisplayName("Session Event는 잘못된 identity와 owner 및 시각 입력을 거부한다")
    void reject_invalid_session_inputs(Long id, User owner, BoardingNotificationEventType type,
            BoardingCheckpoint checkpoint, LocalDateTime detected, LocalDateTime created, LocalDateTime expiry) {
        assertThatThrownBy(() -> session(id, owner, type, checkpoint, detected, created, expiry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Object[]> invalid_inputs() {
        User owner = new User(AuthProvider.GOOGLE, "owner");
        var type = BoardingNotificationEventType.PREPARE_ALIGHT;
        return Stream.of(
                new Object[] {null, owner, type, checkpoint(), NOW, NOW, NOW.plusSeconds(30)},
                new Object[] {0L, owner, type, checkpoint(), NOW, NOW, NOW.plusSeconds(30)},
                new Object[] {-1L, owner, type, checkpoint(), NOW, NOW, NOW.plusSeconds(30)},
                new Object[] {1L, null, type, checkpoint(), NOW, NOW, NOW.plusSeconds(30)},
                new Object[] {1L, owner, null, checkpoint(), NOW, NOW, NOW.plusSeconds(30)},
                new Object[] {1L, owner, type, null, NOW, NOW, NOW.plusSeconds(30)},
                new Object[] {1L, owner, type, checkpoint(), null, NOW, NOW.plusSeconds(30)},
                new Object[] {1L, owner, type, checkpoint(), NOW, null, NOW.plusSeconds(30)},
                new Object[] {1L, owner, type, checkpoint(), NOW, NOW, null},
                new Object[] {1L, owner, type, checkpoint(), NOW.minusSeconds(1), NOW, NOW.plusSeconds(30)},
                new Object[] {1L, owner, type, checkpoint(), NOW, NOW.minusSeconds(1), NOW.plusSeconds(30)},
                new Object[] {1L, owner, type, checkpoint(), NOW, NOW, NOW},
                new Object[] {1L, owner, type, checkpoint(), NOW, NOW, NOW.minusSeconds(1)},
                new Object[] {1L, owner, type, checkpoint(), NOW, NOW, NOW.plusNanos(999)},
                new Object[] {1L, owner, type, checkpoint(), NOW, NOW, NOW.withYear(10000)});
    }

    @Test
    @DisplayName("Session Delivery는 부모 Event 기한을 복사하며 불일치를 거부하고 ownership generation을 보존한다")
    void enforce_session_delivery_expiry() {
        NotificationEvent event = session(1L, user, BoardingNotificationEventType.PREPARE_ALIGHT,
                checkpoint(), NOW, NOW, NOW.plusSeconds(30));
        Device device = new Device(user, UUID.randomUUID().toString(), "a".repeat(64), DevicePlatform.IOS,
                "fixture-fid", 0, NOW);
        NotificationDelivery delivery = new NotificationDelivery(event, device, 7, NOW.plusSeconds(30), NOW);
        assertThat(delivery.getExpiresAt()).isEqualTo(event.getExpiresAt());
        assertThat(delivery.getRecipientOwnershipGeneration()).isEqualTo(7);
        for (LocalDateTime expiry : new LocalDateTime[] {NOW.plusSeconds(29), NOW.plusSeconds(31)}) {
            assertThatThrownBy(() -> new NotificationDelivery(event, device, 7, expiry, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new NotificationDelivery(event, device, -1, event.getExpiresAt(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BoardingCheckpoint checkpoint() {
        return new BoardingCheckpoint(null, 20, 1, BoardingCheckpoint.Evidence.VERIFIED_ORDER_ONLY, null, NOW, null);
    }

    private static NotificationEvent session(Long id, User owner, BoardingNotificationEventType type,
            BoardingCheckpoint checkpoint, LocalDateTime detected, LocalDateTime created, LocalDateTime expiry) {
        return NotificationEvent.forBoardingSession(id, owner, type, checkpoint, detected, created, expiry);
    }
}
