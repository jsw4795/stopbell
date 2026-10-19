package com.stopbell.notification.service;

import static org.assertj.core.api.Assertions.*;
import static com.stopbell.boarding.BoardingSessionFixtures.*;

import java.time.ZoneOffset;
import java.util.List;

import com.stopbell.boarding.domain.BoardingTrackingSnapshot;
import com.stopbell.notification.client.NotificationSendRequest;
import com.stopbell.notification.client.NotificationSourceHint;
import com.stopbell.notification.entity.BoardingNotificationEventType;
import com.stopbell.notification.entity.NotificationSourceType;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationSourceModelTest {
    @ParameterizedTest
    @EnumSource(TransitEventType.class)
    @DisplayName("기존 Alarm 생성자는 네 Event type과 동일한 전송 hint 및 원래 기한을 보존한다")
    void alarm_constructor_compatibility(TransitEventType type) {
        var target = new NotificationDispatchTarget(1, 2, 3, type, 4, 5, 6, 7, "private-fid", UTC_NOW);
        assertThat(target.alarmId()).isEqualTo(3);
        assertThat(target.eventType()).isEqualTo(type);
        assertThat(target.validatedSession()).isNull();
        assertThat(target.request()).isEqualTo(new NotificationSendRequest("private-fid", 3, type, 2, NOW));
    }

    @Test
    @DisplayName("Session attempt는 current revision과 checkpoint를 보존하되 공개 전송 hint와 toString에 노출하지 않는다")
    void session_validation_is_private() {
        var session = session(new User(AuthProvider.GOOGLE, "private-owner"));
        ReflectionTestUtils.setField(session, "id", 3L);
        var validated = BoardingTrackingSnapshot.from(session);
        var source = NotificationSourceHint.boardingSession(3, BoardingNotificationEventType.PREPARE_ALIGHT);
        var target = new NotificationDispatchTarget(1, 2, source, 4, 5, 6, 7, "private-fid", UTC_NOW, validated);
        assertThat(target.alarmId()).isNull();
        assertThat(target.eventType()).isNull();
        assertThat(target.validatedSession().revision()).isEqualTo(session.getRevision());
        assertThat(target.validatedSession().checkpoint()).isEqualTo(session.getCheckpoint());
        assertThat(target.request()).isEqualTo(new NotificationSendRequest("private-fid", source, 2, NOW));
        assertThat(target.request().expiresAt()).isEqualTo(UTC_NOW.toInstant(ZoneOffset.UTC));
        for (String value : List.of(target.toString(), target.request().toString(),
                new NotificationAttemptSnapshot(target, 1, UTC_NOW).toString())) {
            assertThat(value).doesNotContain("private-fid", "private-owner", "vehicle-id", "checkpoint", "revision");
        }
        assertThatThrownBy(() -> new NotificationDispatchTarget(1, 2, source, 4, 5, 6, 7, "private-fid", UTC_NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("누락·혼합 source와 음수 및 sentinel ID를 생성 시 거부한다")
    void rejects_invalid_sources() {
        for (long id : new long[] {-1, 0}) {
            assertThatThrownBy(() -> NotificationSourceHint.alarm(id, TransitEventType.ARRIVED))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> NotificationSourceHint.boardingSession(id, BoardingNotificationEventType.PREPARE_ALIGHT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new NotificationSourceHint(null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationSourceHint(NotificationSourceType.ALARM, 1L, TransitEventType.ARRIVED,
                2L, BoardingNotificationEventType.PREPARE_ALIGHT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationSourceHint(NotificationSourceType.BOARDING_SESSION, 1L,
                TransitEventType.ARRIVED, 2L, BoardingNotificationEventType.PREPARE_ALIGHT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationSourceHint.boardingSession(1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationSourceHint.alarm(1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationSendRequest("private-fid", null, 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
