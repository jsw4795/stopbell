package com.stopbell.boarding.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.stopbell.boarding.domain.*;
import com.stopbell.boarding.domain.BoardingTrackingDecision.Outcome;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.boarding.entity.BoardingStateReason;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionException;
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
    private final BoardingNotificationService notifications;

    public BoardingTrackingStore(BoardingSessionRepository sessions, BoardingTrackingMetadata metadata,
            BoardingTrackingRuntime runtime, PlatformTransactionManager manager,
            @Qualifier("transitMetadataClock") Clock clock, BoardingNotificationService notifications) {
        this.sessions = sessions;
        this.metadata = metadata;
        this.runtime = runtime;
        this.clock = clock;
        this.notifications = notifications;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<BoardingTrackingSnapshot> findOpen() {
        return transaction.execute(status -> sessions.findAllByStatusInOrderByIdAsc(
                List.of(BoardingSessionStatus.TRACKING, BoardingSessionStatus.SUSPENDED)).stream()
                .map(BoardingTrackingSnapshot::from).toList());
    }

    private record Commit(Applied result, BoardingTrackingSnapshot snapshot, Long eventId) { }

    public Applied apply(BoardingTrackingSnapshot expected, BoardingTrackingDecision decision) {
        var attempted = new AtomicReference<Commit>();
        Commit committed;
        try {
            committed = transaction.execute(transactionStatus -> {
                Commit result = applyLocked(expected, decision);
                attempted.set(result);
                return result;
            });
        } catch (DataAccessException | TransactionException exception) {
            // Uncertain persistence must never leave an old readiness grant usable.
            runtime.committed(expected.id(), expected.revision(), false);
            if (exception instanceof DataIntegrityViolationException integrity) {
                if (!isEventIdentityConflict(integrity)) {
                    throw exception;
                }
                // Whole failed transaction is gone. Confirm only the exact logical identity;
                // never replay checkpoint/Event/fan-out after a UNIQUE failure.
                return transaction.execute(status -> {
                    var current = sessions.findForUpdate(expected.id()).orElse(null);
                    if (current == null || !notifications.exists(expected.id(), null)) {
                        throw exception;
                    }
                    return rejected(current).result();
                });
            }
            if (attempted.get() == null || !attempted.get().result().accepted()) {
                throw exception;
            }
            // Commit acknowledgement may be lost. Read exact original result once in a fresh
            // transaction; never replay a mutation or call the Provider again here.
            committed = transaction.execute(status -> {
                var current = sessions.findForUpdate(expected.id()).orElse(null);
                var original = attempted.get();
                if (current != null && original.snapshot().matches(current)
                        && (original.eventId() == null || notifications.exists(expected.id(), original.eventId()))
                        && (!current.getStatus().isOpen() || metadata.matches(original.snapshot()))) {
                    return original;
                }
                return current == null ? new Commit(new Applied(expected.id(), false, -1, null, null), null, null)
                        : rejected(current);
            });
        }
        Applied result = committed.result();
        if (committed.snapshot() != null) {
            if (result.accepted() && result.decision().checkpoint() != null) {
                runtime.verified(committed.snapshot());
            } else if (result.status() != BoardingSessionStatus.TRACKING) {
                runtime.committed(expected.id(), result.revision(), false);
            }
        }
        if (result.accepted() && result.status() == BoardingSessionStatus.TRACKING
                && result.decision().checkpoint() != null && !runtime.isReady(committed.snapshot(), now())) {
            // A newer cancellation/hold may commit between DB acknowledgement and publication.
            return new Applied(result.sessionId(), false, result.revision(), result.status(), null);
        }
        if (result.accepted() && result.decision().outcome() == Outcome.PREPARE_ALIGHT
                && !now().isBefore(result.decision().expiresAt())) {
            var current = result.decision();
            result = new Applied(result.sessionId(), true, result.revision(), result.status(),
                    new BoardingTrackingDecision(Outcome.EXPIRED_CANDIDATE, current.checkpoint(), null,
                            current.remainingOccurrenceGap(), current.observedAt(), current.expiresAt(), current.eventDetectedAt()));
        }
        return result;
    }

    private Commit rejected(BoardingSession session) {
        return new Commit(new Applied(session.getId(), false, session.getRevision(), session.getStatus(), null),
                BoardingTrackingSnapshot.from(session), null);
    }

    private Commit applyLocked(BoardingTrackingSnapshot expected, BoardingTrackingDecision decision) {
        var session = sessions.findForUpdate(expected.id()).orElse(null);
        if (session == null) {
            return new Commit(new Applied(expected.id(), false, -1, null, null), null, null);
        }
        LocalDateTime now = now();
        session.expire(now);
        if (!session.getStatus().isOpen() || !expected.matches(session)) {
            notifications.terminatePending(session);
            return rejected(session);
        }
        // Current structure is checked again under the short apply transaction. Sync is not locked globally.
        BoardingTrackingDecision current = expected.requiresConfirmation()
                ? BoardingTrackingDecision.held(expected.reason()) : decision;
        if (!expected.requiresConfirmation() && !metadata.matches(expected)) {
            current = BoardingTrackingDecision.held(BoardingStateReason.METADATA_CHANGED);
        }
        if (current.outcome() == Outcome.HELD || current.checkpoint() != null) {
            // Close before the mutation can commit. Rollback may conservatively remain closed.
            runtime.committed(session.getId(), session.getRevision(), false);
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
        Long eventId = notifications.prepare(session, expected, current);
        notifications.terminatePending(session);
        sessions.flush();
        return new Commit(new Applied(expected.id(), true, session.getRevision(), session.getStatus(), current),
                BoardingTrackingSnapshot.from(session), eventId);
    }

    public void expire(BoardingTrackingSnapshot expected) {
        var ended = transaction.execute(status -> sessions.findForUpdate(expected.id()).map(session -> {
            session.expire(now());
            notifications.terminatePending(session);
            return BoardingTrackingSnapshot.from(session);
        }).orElse(null));
        if (ended != null && !ended.status().isOpen()) {
            runtime.committed(ended.id(), ended.revision(), false);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean isEventIdentityConflict(DataIntegrityViolationException exception) {
        String expected = "uk_notification_events_boarding_identity";
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getSQLException().getErrorCode() == 1062) {
                String name = violation.getConstraintName();
                return expected.equals(name) || (name != null && name.endsWith("." + expected));
            }
        }
        return false;
    }
}
