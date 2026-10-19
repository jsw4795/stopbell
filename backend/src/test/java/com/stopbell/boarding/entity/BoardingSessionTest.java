package com.stopbell.boarding.entity;

import static com.stopbell.boarding.BoardingSessionFixtures.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;

import com.stopbell.boarding.service.BoardingSessionProperties;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoardingSessionTest {
    private final User user = new User(AuthProvider.GOOGLE, "owner");

    @Test
    @DisplayName("세션은 최초 checkpoint와 고정 수명으로 TRACKING 상태에서 시작한다")
    void start_tracking() {
        var session = session(user);
        assertThat(session.getStatus()).isEqualTo(BoardingSessionStatus.TRACKING);
        assertThat(session.getRevision()).isZero();
        assertThat(session.getExpiresAt()).isEqualTo(UTC_NOW.plusHours(4));
        assertThat(session.getCheckpoint().getObservedAt()).isEqualTo(UTC_NOW);
        assertThat(session.getTraversal().occurrences()).hasSize(3);
    }

    @Test
    @DisplayName("취소와 보류는 revision을 증가시키며 terminal 세션에 늦은 결과를 반영하지 않는다")
    void cancel_and_reject_late_result() {
        var session = session(user);
        session.suspend(0, BoardingStateReason.VEHICLE_MISSING, UTC_NOW.plusSeconds(1));
        assertThat(session.getRevision()).isEqualTo(1);
        assertThatThrownBy(() -> session.cancel(0, UTC_NOW.plusSeconds(2))).isInstanceOf(IllegalStateException.class);
        session.cancel(1, UTC_NOW.plusSeconds(2));
        assertThat(session.getStatus()).isEqualTo(BoardingSessionStatus.CANCELLED);
        assertThat(session.getStateReason()).isEqualTo(BoardingStateReason.USER_CANCELLED);
        assertThat(session.getEndedAt()).isEqualTo(UTC_NOW.plusSeconds(2));
        session.cancel(0, UTC_NOW.plusSeconds(3));
        assertThat(session.getRevision()).isEqualTo(2);
        assertThatThrownBy(() -> session.suspend(2, BoardingStateReason.STALE_PROGRESS, UTC_NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("수명 경계와 같거나 늦으면 만료되며 반복 만료는 revision과 종료 시각을 바꾸지 않는다")
    void exact_expiry() {
        var session = session(user);
        session.expire(UTC_NOW.plusHours(4).minusNanos(1000));
        assertThat(session.getStatus()).isEqualTo(BoardingSessionStatus.TRACKING);
        session.expire(UTC_NOW.plusHours(4));
        session.expire(UTC_NOW.plusHours(5));
        assertThat(session.getStatus()).isEqualTo(BoardingSessionStatus.EXPIRED);
        assertThat(session.getRevision()).isEqualTo(1);
        assertThat(session.getEndedAt()).isEqualTo(UTC_NOW.plusHours(4));
    }

    @Test
    @DisplayName("snapshot JSON은 순서와 목적 분류를 복원하며 구조 변경 시 fingerprint가 달라진다")
    void snapshot_round_trip() {
        var traversal = verified(metadata()).traversal();
        assertThat(BoardingTraversalSnapshot.fromJson(traversal.toJson())).isEqualTo(traversal);
        var unknown = new BoardingTraversalSnapshot(1, traversal.provider(), traversal.externalRouteId(), null,
                traversal.occurrences(), traversal.targetIndex(), BoardingTraversalSnapshot.AlightAvailability.UNKNOWN, null);
        assertThat(unknown.fingerprint()).isNotEqualTo(traversal.fingerprint());
        assertThatThrownBy(() -> new BoardingTraversalSnapshot(2, traversal.provider(), traversal.externalRouteId(), null,
                traversal.occurrences(), 2, BoardingTraversalSnapshot.AlightAvailability.UNKNOWN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("순서 중복과 범위 밖 목적지 및 근거 없는 하차 가능 분류는 거부한다")
    void reject_invalid_traversal() {
        var traversal = verified(metadata()).traversal();
        assertThatThrownBy(() -> new BoardingTraversalSnapshot(1, traversal.provider(), "route", null,
                List.of(traversal.occurrences().getFirst(), traversal.occurrences().getFirst()), 1,
                BoardingTraversalSnapshot.AlightAvailability.UNKNOWN, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoardingTraversalSnapshot(1, traversal.provider(), "route", null,
                traversal.occurrences(), 3, BoardingTraversalSnapshot.AlightAvailability.UNKNOWN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoardingTraversalSnapshot(1, traversal.provider(), "route", null,
                traversal.occurrences(), 2, BoardingTraversalSnapshot.AlightAvailability.ALLOWED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("order-only checkpoint는 Stop ID를 합성하지 않고 실제 snapshot과 대응을 검증한다")
    void preserve_order_only_evidence() {
        var checkpoint = new BoardingCheckpoint(null, 10, 0, BoardingCheckpoint.Evidence.VERIFIED_ORDER_ONLY,
                null, UTC_NOW.plusNanos(789), null);
        checkpoint.validateAgainst(verified(metadata()).traversal());
        assertThat(checkpoint.getStopExternalId()).isNull();
        assertThat(checkpoint.getObservedAt()).isEqualTo(UTC_NOW);
        assertThatThrownBy(() -> new BoardingCheckpoint("invented", 10, 0,
                BoardingCheckpoint.Evidence.VERIFIED_ORDER_ONLY, null, UTC_NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoardingCheckpoint("wrong", 10, 0, BoardingCheckpoint.Evidence.ID_ORDER,
                null, UTC_NOW, null).validateAgainst(verified(metadata()).traversal()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Provider 제공시각이 수신보다 미래이거나 상태 시각이 역행하면 거부한다")
    void reject_time_inconsistency() {
        assertThatThrownBy(() -> new BoardingCheckpoint("s1", 10, 0, BoardingCheckpoint.Evidence.ID_ORDER,
                null, UTC_NOW, UTC_NOW.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session(user).cancel(0, UTC_NOW.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("세션 수명은 기본 4시간이며 0과 음수 및 microsecond 미만과 overflow는 거부한다")
    void validate_lifetime_configuration() {
        var properties = new BoardingSessionProperties();
        assertThat(properties.getMaxLifetime()).isEqualTo(Duration.ofHours(4));
        for (var invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> properties.setMaxLifetime(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
