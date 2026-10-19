package com.stopbell.boarding.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.stopbell.boarding.domain.*;
import com.stopbell.boarding.domain.BoardingTrackingDecision.Outcome;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import com.stopbell.boarding.entity.BoardingStateReason;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BoardingTrackingStore {
    public record Applied(Long sessionId, boolean accepted, long revision, BoardingSessionStatus status, BoardingTrackingDecision decision) { }
    private final BoardingSessionRepository sessions;
    private final BoardingTrackingMetadata metadata;
    private final BoardingTrackingRuntime runtime;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public BoardingTrackingStore(BoardingSessionRepository sessions, BoardingTrackingMetadata metadata,
            BoardingTrackingRuntime runtime, PlatformTransactionManager manager,
            @Qualifier("transitMetadataClock") Clock clock) {
        this.sessions = sessions;
        this.metadata = metadata;
        this.runtime = runtime;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<BoardingTrackingSnapshot> findOpen() {
        return transaction.execute(status -> sessions.findAllByStatusInOrderByIdAsc(
                List.of(BoardingSessionStatus.TRACKING, BoardingSessionStatus.SUSPENDED)).stream()
                .map(BoardingTrackingSnapshot::from).toList());
    }

    public Applied apply(BoardingTrackingSnapshot expected, BoardingTrackingDecision decision) {
        Applied result = transaction.execute(transactionStatus -> {
            var session = sessions.findForUpdate(expected.id()).orElse(null);
            if (session == null) {
                return new Applied(expected.id(), false, -1, null, null);
            }
            LocalDateTime now = now();
            session.expire(now);
            if (!session.getStatus().isOpen() || !expected.matches(session) || !runtime.isAdmitted(expected.id())) {
                return new Applied(expected.id(), false, session.getRevision(), session.getStatus(), null);
            }
            // Current structure is checked again under the short apply transaction. Sync is not locked globally.
            BoardingTrackingDecision current = expected.requiresConfirmation()
                    ? BoardingTrackingDecision.held(expected.reason()) : decision;
            if (!expected.requiresConfirmation() && !metadata.matches(expected)) {
                current = BoardingTrackingDecision.held(BoardingStateReason.METADATA_CHANGED);
            }
            if (current.outcome() == Outcome.HELD) {
                session.suspend(expected.revision(), current.reason(), now);
            } else if (current.checkpoint() != null) {
                if (current.observedAt().isAfter(now)
                        || current.observedAt().isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS))
                        || (current.checkpoint().getProviderDataTime() != null && (current.checkpoint().getProviderDataTime().isAfter(now)
                            || current.checkpoint().getProviderDataTime().isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS))))) {
                    current = BoardingTrackingDecision.held(BoardingStateReason.STALE_PROGRESS);
                    session.suspend(expected.revision(), current.reason(), now);
                } else {
                    session.track(expected.revision(), current.checkpoint(), current.reason(), now);
                }
            }
            sessions.flush();
            return new Applied(expected.id(), true, session.getRevision(), session.getStatus(), current);
        });
        // execute returns only after commit. An exception never publishes memory readiness or a candidate.
        if (result.accepted()) {
            runtime.committed(expected.id(), result.revision(), result.status() == BoardingSessionStatus.TRACKING);
            var current = result.decision();
            if (current.outcome() == Outcome.PREPARE_ALIGHT && !now().isBefore(current.expiresAt())) {
                current = new BoardingTrackingDecision(Outcome.EXPIRED_CANDIDATE, current.checkpoint(), null,
                        current.remainingOccurrenceGap(), current.observedAt(), current.expiresAt());
                result = new Applied(result.sessionId(), true, result.revision(), result.status(), current);
            }
        }
        if (result.status() != null && !result.status().isOpen()) {
            runtime.forget(expected.id());
        }
        return result;
    }

    public void expire(BoardingTrackingSnapshot expected) {
        transaction.executeWithoutResult(status -> sessions.findForUpdate(expected.id()).ifPresent(session -> session.expire(now())));
        runtime.forget(expected.id());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
