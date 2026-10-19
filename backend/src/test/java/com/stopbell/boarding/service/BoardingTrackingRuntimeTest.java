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
        runtime.committed(1L, 3, true);
        runtime.committed(1L, 2, true);
        runtime.retainLoaded(Set.of(1L), Set.of(1L));
        assertThat(runtime.response(session, UTC_NOW.plusSeconds(3)).evaluationStatus()).isEqualTo(EvaluationStatus.READY);
        runtime.forget(1L);
        runtime.committed(1L, 4, true);
        assertThat(runtime.response(session, UTC_NOW.plusSeconds(3)).evaluationStatus()).isEqualTo(EvaluationStatus.RECOVERY_PENDING);
    }
}
