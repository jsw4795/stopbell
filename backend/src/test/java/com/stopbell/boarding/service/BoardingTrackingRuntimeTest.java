package com.stopbell.boarding.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.stopbell.boarding.BoardingSessionFixtures.*;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import com.stopbell.transit.service.VehiclePollingCycle;
import com.stopbell.boarding.entity.BoardingCheckpoint;
import com.stopbell.boarding.dto.BoardingSessionResponse.EvaluationStatus;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoardingTrackingRuntimeTest {
    @Test
    @DisplayName("DB 목록 조회 중 새로 생성된 세션의 runtime 등록은 이전 목록 정리로 제거하지 않는다")
    void creation_during_list_read() {
        var runtime = new BoardingTrackingRuntime();
        runtime.admitCreated(1L);
        var store = mock(BoardingTrackingStore.class);
        when(store.findOpen()).thenAnswer(invocation -> {
            runtime.admitCreated(2L);
            return List.of();
        });
        var polling = new BoardingSessionPollingService(store, mock(BoardingTrackingMetadata.class), runtime,
                mock(BoardingVehicleTracker.class), mock(BoardingProgressEvaluator.class), Clock.systemUTC());
        assertThat(polling.poll(mock(VehiclePollingCycle.class))).isEmpty();
        assertThat(runtime.admittedIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("같은 프로세스의 뒤늦은 commit 응답은 더 최신 revision의 readiness를 덮어쓰지 않는다")
    void late_memory_publication() {
        var session = session(new User(AuthProvider.GOOGLE, "owner"));
        ReflectionTestUtils.setField(session, "id", 1L);
        for (int i = 1; i <= 3; i++) {
            session.track(i - 1, new BoardingCheckpoint("s1", 10, 0, BoardingCheckpoint.Evidence.ID_ORDER,
                    null, UTC_NOW.plusSeconds(i), UTC_NOW.plusSeconds(i - 1)), null, UTC_NOW.plusSeconds(i));
        }
        var runtime = new BoardingTrackingRuntime();
        runtime.admitCreated(1L);
        runtime.verified(com.stopbell.boarding.domain.BoardingTrackingSnapshot.from(session));
        runtime.committed(1L, 2, true);
        runtime.retainLoaded(Set.of(1L), Set.of(1L));
        assertThat(runtime.response(session, UTC_NOW.plusSeconds(3)).evaluationStatus()).isEqualTo(EvaluationStatus.READY);
        runtime.forget(1L);
        runtime.committed(1L, 4, true);
        assertThat(runtime.response(session, UTC_NOW.plusSeconds(3)).evaluationStatus()).isEqualTo(EvaluationStatus.RECOVERY_PENDING);
    }
    @Test
    @DisplayName("보류한 revision은 이전 허용값을 남기지 않고 같은 revision과 더 오래된 callback의 재허용도 차단한다")
    void close_revokes_previous_readiness() {
        var session = session(new User(AuthProvider.GOOGLE, "owner"));
        ReflectionTestUtils.setField(session, "id", 1L);
        var runtime = new BoardingTrackingRuntime();
        var original = com.stopbell.boarding.domain.BoardingTrackingSnapshot.from(session);
        runtime.verified(original);
        assertThat(runtime.isReady(original, UTC_NOW)).isTrue();
        runtime.committed(1L, 1, false);
        runtime.committed(1L, 0, true);
        runtime.committed(1L, 1, true);
        runtime.verified(original);
        assertThat(runtime.isReady(original, UTC_NOW)).isFalse();
    }

    @Test
    @DisplayName("freshness가 끝난 revision의 허용을 닫고 같은 관측의 늦은 검증 callback으로 다시 열지 않는다")
    void expired_evidence_cannot_reopen() {
        var session = session(new User(AuthProvider.GOOGLE, "owner"));
        ReflectionTestUtils.setField(session, "id", 1L);
        var snapshot = com.stopbell.boarding.domain.BoardingTrackingSnapshot.from(session);
        var runtime = new BoardingTrackingRuntime();
        runtime.verified(snapshot);
        assertThat(runtime.isReady(snapshot, UTC_NOW.plusSeconds(61))).isFalse();
        runtime.verified(snapshot);
        assertThat(runtime.isReady(snapshot, UTC_NOW)).isFalse();
    }

    @Test
    @DisplayName("새 프로세스는 DB TRACKING이나 일반 commit 알림만으로 등록되지 않으며 정확한 검증 revision만 READY다")
    void unknown_process_and_revision_mismatch() {
        var session = session(new User(AuthProvider.GOOGLE, "owner"));
        ReflectionTestUtils.setField(session, "id", 1L);
        var original = com.stopbell.boarding.domain.BoardingTrackingSnapshot.from(session);
        var runtime = new BoardingTrackingRuntime();
        assertThat(runtime.isReady(original, UTC_NOW)).isFalse();
        runtime.committed(1L, 0, true);
        assertThat(runtime.isReady(original, UTC_NOW)).isFalse();
        runtime.forget(1L);
        runtime.verified(original);
        session.suspend(0, com.stopbell.boarding.entity.BoardingStateReason.STALE_PROGRESS, UTC_NOW);
        assertThat(runtime.response(session, UTC_NOW).remainingOccurrenceGap()).isNull();
        assertThat(runtime.isReady(com.stopbell.boarding.domain.BoardingTrackingSnapshot.from(session), UTC_NOW)).isFalse();
    }
    @Test
    @DisplayName("수천 복구 대기와 보류 watermark는 READY window를 점유하지 않고 ID cursor는 bounded 순환한다")
    void bounded_ready_windows_exclude_recovery_backlog() {
        var runtime = new BoardingTrackingRuntime();
        for (long id = 1; id <= 2000; id++) {
            runtime.admitCreated(id);
            if (id % 2 == 0) { runtime.committed(id, 1, false); }
        }
        for (long id = 2001; id <= 2003; id++) {
            var session = session(new User(AuthProvider.GOOGLE, "owner-" + id));
            ReflectionTestUtils.setField(session, "id", id);
            runtime.verified(com.stopbell.boarding.domain.BoardingTrackingSnapshot.from(session));
        }
        var first = runtime.readyWindow(0, 2, UTC_NOW);
        assertThat(first.ids()).containsExactly(2001L, 2002L);
        var second = runtime.readyWindow(first.cursor(), 2, UTC_NOW);
        assertThat(second.ids()).containsExactly(2003L);
        assertThat(runtime.readyWindow(second.cursor(), 2, UTC_NOW).ids()).containsExactly(2001L, 2002L);
        runtime.committed(2001L, 1, false);
        assertThat(runtime.readyWindow(0, 2, UTC_NOW).ids()).containsExactly(2002L, 2003L);
        assertThat(runtime.readyWindow(0, 2, UTC_NOW.plusSeconds(61)).ids()).isEmpty();
        assertThat(runtime.readyWindow(0, 2, UTC_NOW).ids()).isEmpty();
    }

}
