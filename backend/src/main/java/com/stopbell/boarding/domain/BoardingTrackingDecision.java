package com.stopbell.boarding.domain;

import java.time.LocalDateTime;

import com.stopbell.boarding.entity.BoardingCheckpoint;
import com.stopbell.boarding.entity.BoardingStateReason;

/** Candidate only: TASK-906 owns UNIQUE(sessionId, PREPARE_ALIGHT), Event and Delivery persistence. */
public record BoardingTrackingDecision(
        Outcome outcome, BoardingCheckpoint checkpoint, BoardingStateReason reason,
        Integer remainingOccurrenceGap, LocalDateTime observedAt, LocalDateTime expiresAt
) {
    public enum Outcome { NO_CANDIDATE, PREPARE_ALIGHT, HELD, ARRIVAL_OBSERVED, PASSED_OBSERVED, EXPIRED_CANDIDATE }

    public static BoardingTrackingDecision held(BoardingStateReason reason) {
        return new BoardingTrackingDecision(Outcome.HELD, null, reason, null, null, null);
    }
}
