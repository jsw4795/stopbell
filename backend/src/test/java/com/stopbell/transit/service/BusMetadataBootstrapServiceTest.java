package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stopbell.transit.domain.*;
import com.stopbell.transit.entity.TransitMetadataCollectionRun;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import com.stopbell.transit.metadata.TagoMetadataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class BusMetadataBootstrapServiceTest {
    private final SeoulCsvMetadataSource source = mock(SeoulCsvMetadataSource.class);
    private final BusMetadataSyncService sync = mock(BusMetadataSyncService.class);
    private final TransitMetadataCollectionHistoryService history = mock(TransitMetadataCollectionHistoryService.class);
    private final TransitMetadataDiffAnalysisService analysis = mock(TransitMetadataDiffAnalysisService.class);
    private final TransitMetadataUnsafeChangeReviewService review = mock(TransitMetadataUnsafeChangeReviewService.class);
    private final TransitMetadataArchiveService archive = mock(TransitMetadataArchiveService.class);
    private final TransitMetadataCollectionRun run = mock(TransitMetadataCollectionRun.class);
    private final SeoulCsvMetadataSource.SeoulCsvSnapshot snapshot = mock(SeoulCsvMetadataSource.SeoulCsvSnapshot.class);

    private BusMetadataBootstrapService bootstrap() {
        when(source.provider()).thenReturn(TransitProvider.SEOUL_BUS);
        when(run.getId()).thenReturn(42L);
        when(history.start(TransitProvider.SEOUL_BUS)).thenReturn(run);
        return new BusMetadataBootstrapService(List.of(source), sync, history, archive, analysis, review, Clock.systemUTC());
    }

    private void valid() {
        doReturn(snapshot).when(source).fetchSnapshotWithRawRows(42L, archive);
        when(sync.prepareSeoulSnapshot(eq(source), eq(snapshot), any())).thenReturn(() -> {});
        var diff = new BusMetadataDiffService.Result(TransitProvider.SEOUL_BUS, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(analysis.analyzeAndStore(42L, source, snapshot)).thenReturn(new TransitMetadataDiffAnalysisService.Analysis(null, diff));
    }

    private void validTago(TagoMetadataSource.TagoSnapshot snapshot) {
        var diff = new BusMetadataDiffService.Result(TransitProvider.TAGO, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(analysis.analyzeAndStore(42L, snapshot)).thenReturn(new TransitMetadataDiffAnalysisService.Analysis(null, diff));
    }

    @Test @DisplayName("수집과 검증 뒤 동기화하고 완료 이력을 기록한다")
    void transitions_after_actual_work() {
        var service = bootstrap(); valid();
        service.bootstrap(TransitProvider.SEOUL_BUS);
        InOrder order = inOrder(history, archive, source, sync, analysis);
        order.verify(history).start(TransitProvider.SEOUL_BUS);
        order.verify(archive).validateStorage();
        order.verify(source).fetchSnapshotWithRawRows(42L, archive);
        order.verify(sync).prepareSeoulSnapshot(eq(source), eq(snapshot), any());
        order.verify(history).collected(42L);
        order.verify(analysis).analyzeAndStore(42L, source, snapshot);
        order.verify(history).syncing(42L);
        order.verify(history).complete(42L);
    }

    @Test @DisplayName("원본 검증 실패는 이력에 기록되고 재시도가 가능하다")
    void records_validation_failure_and_releases_guard() {
        var service = bootstrap();
        when(source.fetchSnapshotWithRawRows(42L, archive)).thenThrow(new IllegalStateException("secret"));
        assertThatThrownBy(() -> service.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageNotContaining("secret");
        verify(history).fail(42L, TransitCollectionFailureStage.VALIDATION, TransitCollectionFailureCode.INVALID_SOURCE);
        valid(); service.bootstrap(TransitProvider.SEOUL_BUS);
        verify(history, times(2)).start(TransitProvider.SEOUL_BUS);
    }

    @Test @DisplayName("분석 장애는 동기화를 막고 원문 없이 기록하며 재시도할 수 있다")
    void analysis_failure_blocks_sync_and_releases_guard() {
        var service = bootstrap(); valid();
        var safe = analysis.analyzeAndStore(42L, source, snapshot);
        clearInvocations(analysis);
        doThrow(new IllegalStateException("secret")).doReturn(safe).when(analysis).analyzeAndStore(42L, source, snapshot);
        assertThatThrownBy(() -> service.bootstrap(TransitProvider.SEOUL_BUS))
                .hasMessageContaining("VALIDATION").hasMessageContaining("UNEXPECTED_FAILURE").hasMessageNotContaining("secret");
        verify(history).fail(42L, TransitCollectionFailureStage.VALIDATION, TransitCollectionFailureCode.UNEXPECTED_FAILURE);
        verify(history, never()).syncing(anyLong()); verify(history, never()).complete(anyLong());
        service.bootstrap(TransitProvider.SEOUL_BUS);
        verify(history, times(2)).start(TransitProvider.SEOUL_BUS);
    }

    @Test @DisplayName("위험 변경은 동기화 상태 진입 전에 차단하고 다음 실행을 허용한다")
    void unsafe_change_blocks_before_syncing_and_releases_guard() {
        var service = bootstrap(); valid();
        var safe = analysis.analyzeAndStore(42L, source, snapshot);
        clearInvocations(analysis);
        var unsafe = new BusMetadataDiffService.Result(TransitProvider.SEOUL_BUS, List.of(), List.of(), List.of("old"), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(analysis.analyzeAndStore(42L, source, snapshot)).thenReturn(new TransitMetadataDiffAnalysisService.Analysis(null, unsafe), safe);
        assertThatThrownBy(() -> service.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("code=UNSAFE_CHANGE");
        verify(history).fail(42L, TransitCollectionFailureStage.VALIDATION, TransitCollectionFailureCode.UNSAFE_CHANGE);
        verify(history, never()).syncing(anyLong());
        verify(history, never()).complete(anyLong());
        service.bootstrap(TransitProvider.SEOUL_BUS);
        verify(history).syncing(42L);
        verify(history).complete(42L);
    }

    @Test @DisplayName("분석 단계에서 발견한 불완전 Snapshot은 원본 검증 실패로 기록한다")
    void invalid_snapshot_is_not_classified_as_infrastructure_failure() {
        var service = bootstrap(); valid();
        when(analysis.analyzeAndStore(42L, source, snapshot)).thenThrow(new TransitMetadataDiffAnalysisService.InvalidSnapshotException());
        assertThatThrownBy(() -> service.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("code=INVALID_SOURCE");
        verify(history).fail(42L, TransitCollectionFailureStage.VALIDATION, TransitCollectionFailureCode.INVALID_SOURCE);
        verify(history, never()).syncing(anyLong()); verify(history, never()).complete(anyLong());
    }

    @Test @DisplayName("분석 중 인터럽트는 플래그를 보존하고 동기화를 막는다")
    void analysis_interruption_is_preserved() {
        var service = bootstrap(); valid();
        when(analysis.analyzeAndStore(42L, source, snapshot)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt(); throw new IllegalStateException();
        });
        try {
            assertThatThrownBy(() -> service.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("interrupted=true");
            org.assertj.core.api.Assertions.assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(history).interrupt(42L); verify(history, never()).syncing(anyLong());
        } finally { Thread.interrupted(); }
    }

    @Test @DisplayName("동기화 실패는 완료하지 않고 고정 오류 코드로 기록한다")
    void records_sync_failure() {
        var service = bootstrap(); valid();
        when(sync.prepareSeoulSnapshot(eq(source), eq(snapshot), any())).thenReturn(() -> { throw new IllegalStateException("secret"); });
        assertThatThrownBy(() -> service.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageNotContaining("secret");
        verify(history).fail(42L, TransitCollectionFailureStage.SYNC, TransitCollectionFailureCode.SYNC_FAILED);
        verify(history, never()).complete(anyLong());
    }

    @Test @DisplayName("완료 기록 오류는 멱등 재시도하고 동기화 실패로 표시하지 않는다")
    void retries_completion_without_marking_sync_failed() {
        var service = bootstrap(); valid();
        doThrow(new IllegalStateException()).when(history).complete(42L);
        assertThatThrownBy(() -> service.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("completion history is uncertain");
        verify(history, times(2)).complete(42L);
        verify(analysis, times(1)).analyzeAndStore(42L, source, snapshot);
        verify(source, times(1)).fetchSnapshotWithRawRows(42L, archive);
        verify(history, never()).fail(anyLong(), any(), any());
    }

    @Test @DisplayName("동일 Provider 중복 요청은 새 이력을 만들지 않는다")
    void rejects_concurrent_request() throws Exception {
        var service = bootstrap(); valid();
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(source.fetchSnapshotWithRawRows(42L, archive)).thenAnswer(invocation -> {
            started.countDown(); release.await(); return snapshot;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.bootstrap(TransitProvider.SEOUL_BUS));
            try {
                org.assertj.core.api.Assertions.assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("already running");
                verify(history, times(1)).start(TransitProvider.SEOUL_BUS);
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @Test @DisplayName("수집 스레드 중단은 인터럽트 이력을 남긴다")
    void records_interruption() {
        var service = bootstrap();
        when(source.fetchSnapshotWithRawRows(42L, archive)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt(); throw new IllegalStateException();
        });
        try {
            assertThatThrownBy(() -> service.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("interrupted=true");
            org.assertj.core.api.Assertions.assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(history).interrupt(42L);
            verify(history, never()).complete(anyLong());
        } finally { Thread.interrupted(); }
    }
    @Test @DisplayName("아카이브 설정 오류는 수집 전에 실패 이력을 남긴다")
    void archive_failure_prevents_fetch() {
        var service = bootstrap();
        doThrow(new TransitMetadataArchiveService.ArchiveFailure()).when(archive).validateStorage();
        assertThatThrownBy(() -> service.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("code=UNEXPECTED_FAILURE");
        verify(history).fail(42L, TransitCollectionFailureStage.COLLECTION, TransitCollectionFailureCode.UNEXPECTED_FAILURE);
        verify(source, never()).fetchSnapshotWithRawRows(anyLong(), any());
        verifyNoInteractions(sync);
    }

    @Test @DisplayName("이력 저장도 실패하면 두 실패를 민감한 원문 없이 알린다")
    void reports_failure_to_record_failure() {
        var service = bootstrap();
        when(source.fetchSnapshotWithRawRows(42L, archive)).thenThrow(new IllegalStateException("secret provider"));
        doThrow(new IllegalStateException("secret database")).when(history).fail(anyLong(), any(), any());
        assertThatThrownBy(() -> service.bootstrap(TransitProvider.SEOUL_BUS))
                .hasMessageContaining("history recording also failed").hasMessageContaining("failureType=IllegalStateException")
                .hasMessageContaining("historyFailureType=IllegalStateException").hasMessageNotContaining("secret");
    }

    @Test @DisplayName("완료 기록 재시도가 성공하면 실패 상태를 남기지 않는다")
    void successful_completion_retry() {
        var service = bootstrap(); valid();
        doThrow(new IllegalStateException()).doReturn(run).when(history).complete(42L);
        service.bootstrap(TransitProvider.SEOUL_BUS);
        verify(history, times(2)).complete(42L);
        verify(analysis, times(1)).analyzeAndStore(42L, source, snapshot);
        verify(source, times(1)).fetchSnapshotWithRawRows(42L, archive);
        verify(history, never()).fail(anyLong(), any(), any());
    }

    @Test @DisplayName("TAGO와 GBIS 수집은 TAGO 실행 ID로 연결한다")
    void tago_uses_tago_run_id() {
        TagoMetadataSource tago = mock(TagoMetadataSource.class);
        TagoMetadataSource.TagoSnapshot tagoSnapshot = mock(TagoMetadataSource.TagoSnapshot.class);
        when(tago.provider()).thenReturn(TransitProvider.TAGO);
        when(run.getId()).thenReturn(42L);
        when(history.start(TransitProvider.TAGO)).thenReturn(run);
        when(tago.fetchSnapshotWithRawRows(42L, archive)).thenReturn(tagoSnapshot);
        when(sync.prepareTagoSnapshot(eq(tagoSnapshot), any())).thenReturn(() -> {});
        validTago(tagoSnapshot);
        new BusMetadataBootstrapService(List.of(tago), sync, history, archive, analysis, review, Clock.systemUTC()).bootstrap(TransitProvider.TAGO);
        verify(tago).fetchSnapshotWithRawRows(42L, archive);
        verify(analysis).analyzeAndStore(42L, tagoSnapshot);
        verify(history).complete(42L);
        verify(history, times(1)).start(any());
    }

    @Test @DisplayName("서로 다른 Provider는 동시에 실행할 수 있다")
    void allows_different_providers() throws Exception {
        var seoul = source;
        var tago = mock(TagoMetadataSource.class);
        var tagoSnapshot = mock(TagoMetadataSource.TagoSnapshot.class);
        when(seoul.provider()).thenReturn(TransitProvider.SEOUL_BUS);
        when(tago.provider()).thenReturn(TransitProvider.TAGO);
        when(run.getId()).thenReturn(42L);
        when(history.start(any())).thenReturn(run);
        when(tago.fetchSnapshotWithRawRows(42L, archive)).thenReturn(tagoSnapshot);
        when(sync.prepareTagoSnapshot(eq(tagoSnapshot), any())).thenReturn(() -> {});
        validTago(tagoSnapshot);
        valid();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); release.await(); return snapshot; }).when(seoul).fetchSnapshotWithRawRows(42L, archive);
        var service = new BusMetadataBootstrapService(List.of(seoul, tago), sync, history, archive, analysis, review, Clock.systemUTC());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.bootstrap(TransitProvider.SEOUL_BUS));
            try {
                org.assertj.core.api.Assertions.assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                service.bootstrap(TransitProvider.TAGO);
                verify(tago).fetchSnapshotWithRawRows(42L, archive);
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
    }

}
