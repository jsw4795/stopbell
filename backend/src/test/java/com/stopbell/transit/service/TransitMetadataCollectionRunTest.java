package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.*;

import com.stopbell.transit.domain.*;
import com.stopbell.transit.entity.TransitMetadataCollectionRun;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransitMetadataCollectionRunTest {
    private static final Instant START = Instant.parse("2026-11-07T00:00:00Z");
    private final TransitMetadataCollectionRun run = new TransitMetadataCollectionRun(TransitProvider.TAGO, START);

    @Test @DisplayName("수집 실행은 제공자와 시작 시각을 기록한다")
    void starts_collecting() {
        assertThat(run.getProvider()).isEqualTo(TransitProvider.TAGO);
        assertThat(run.getStatus()).isEqualTo(TransitCollectionStatus.COLLECTING);
        assertThat(run.getStartedAt()).isEqualTo(START);
        assertThat(run.getCreatedAt()).isEqualTo(START);
        assertThat(run.getFinishedAt()).isNull();
    }

    @Test @DisplayName("정상 전이와 완료 재요청은 최초 완료 시각을 보존한다")
    void completes_and_retries_without_changing_time() {
        run.collected(START.plusSeconds(1));
        run.syncing(START.plusSeconds(2));
        run.complete(START.plusSeconds(3));
        run.complete(START.plusSeconds(4));
        assertThat(run.getStatus()).isEqualTo(TransitCollectionStatus.COMPLETED);
        assertThat(run.getFinishedAt()).isEqualTo(START.plusSeconds(3));
        assertThat(run.getUpdatedAt()).isEqualTo(START.plusSeconds(3));
        assertThatThrownBy(() -> run.interrupt(START.plusSeconds(5))).isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("잘못된 순서와 종료 상태의 전이는 거부한다")
    void rejects_invalid_transitions() {
        assertThatThrownBy(() -> run.complete(START)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.syncing(START)).isInstanceOf(IllegalStateException.class);
        run.interrupt(START.plusSeconds(1));
        assertThatThrownBy(() -> run.collected(START.plusSeconds(2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.fail(TransitCollectionFailureStage.COLLECTION,
                TransitCollectionFailureCode.SOURCE_UNAVAILABLE, START.plusSeconds(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("실패는 고정된 안전 메시지만 기록하고 충돌하는 재요청을 거부한다")
    void records_safe_failure_and_rejects_conflict() {
        run.fail(TransitCollectionFailureStage.COLLECTION, TransitCollectionFailureCode.SOURCE_UNAVAILABLE, START.plusSeconds(1));
        run.fail(TransitCollectionFailureStage.COLLECTION, TransitCollectionFailureCode.SOURCE_UNAVAILABLE, START.plusSeconds(2));
        assertThat(run.getFailureMessage()).isEqualTo("Source unavailable");
        assertThat(run.getFinishedAt()).isEqualTo(START.plusSeconds(1));
        assertThatThrownBy(() -> run.fail(TransitCollectionFailureStage.SYNC,
                TransitCollectionFailureCode.SYNC_FAILED, START.plusSeconds(3))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.complete(START.plusSeconds(3))).isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("수집 중단 재요청은 최초 종료 시각을 보존한다")
    void interrupts_idempotently() {
        run.collected(START.plusSeconds(1));
        run.interrupt(START.plusSeconds(2));
        run.interrupt(START.plusSeconds(3));
        assertThat(run.getStatus()).isEqualTo(TransitCollectionStatus.INTERRUPTED);
        assertThat(run.getFinishedAt()).isEqualTo(START.plusSeconds(2));
        assertThat(run.getFailureMessage()).isNull();
    }

    @Test @DisplayName("시간 역행과 누락된 실패 정보는 상태를 변경하지 않는다")
    void rejects_invalid_inputs() {
        assertThatThrownBy(() -> run.collected(START.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run.fail(null, TransitCollectionFailureCode.UNEXPECTED_FAILURE, START)).isInstanceOf(NullPointerException.class);
        assertThat(run.getStatus()).isEqualTo(TransitCollectionStatus.COLLECTING);
    }
}
