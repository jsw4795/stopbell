package com.stopbell.boarding.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.stopbell.boarding.domain.BoardingTrackingSnapshot;
import com.stopbell.boarding.dto.ReconfirmBoardingSessionRequest;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import com.stopbell.boarding.dto.BoardingSessionResponse;
import com.stopbell.boarding.dto.CreateBoardingSessionRequest;
import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.service.BoardingCandidateMetadataService;
import com.stopbell.transit.service.BoardingCandidateReferenceStore;
import com.stopbell.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BoardingSessionService {
    private final BoardingSessionRepository sessions;
    private final UserRepository users;
    private final BoardingSessionStartValidator validator;
    private final BoardingCandidateMetadataService metadata;
    private final BoardingSessionProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final BoardingTrackingRuntime runtime;
    private final BoardingNotificationService notifications;

    public BoardingSessionService(BoardingSessionRepository sessions, UserRepository users,
            BoardingSessionStartValidator validator, BoardingCandidateMetadataService metadata,
            BoardingSessionProperties properties, PlatformTransactionManager manager,
            @Qualifier("transitMetadataClock") Clock clock, BoardingTrackingRuntime runtime,
            BoardingNotificationService notifications) {
        this.sessions = sessions;
        this.users = users;
        this.validator = validator;
        this.metadata = metadata;
        this.properties = properties;
        this.clock = clock;
        this.runtime = runtime;
        this.notifications = notifications;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public CreationResult create(Long userId, CreateBoardingSessionRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        Optional<BoardingSessionResponse> existing = transaction.execute(status -> {
            authenticated(userId);
            // Avoid locking a missing UUID range before Provider I/O.
            if (!sessions.existsByUserIdAndClientRequestId(userId, request.clientRequestId())) {
                return Optional.empty();
            }
            return sessions.findRequestForUpdate(userId, request.clientRequestId()).map(session -> {
                expire(session);
                return existing(session, request);
            });
        });
        if (existing.isPresent()) {
            return new CreationResult(existing.orElseThrow(), false);
        }
        if (request.legacyPrepareNotificationEnabled() != null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        // All Provider I/O occurs here, before either User or Session lock is acquired.
        VerifiedBoardingStart start = validator.validate(userId, request.candidateRef(), request.confirmedVehicleNumber());
        CreationResult result;
        try {
            result = createTransaction(userId, request, start);
        } catch (DataAccessException | TransactionException exception) {
            if (exception instanceof DataIntegrityViolationException integrity && !knownUnique(integrity)) {
                throw exception;
            }
            // One bounded reconciliation in a fresh transaction/persistence context. Use the
            // original request/evidence; never refetch a different vehicle or blindly upsert.
            result = createTransaction(userId, request, start);
        }
        // A duplicate read after restart must not admit an old session without recovery.
        if (result.created()) {
            runtime.admitCreated(result.response().id());
            // Registration itself validated fresh current progress and metadata before commit.
            // Publish only that acknowledged snapshot; a later cancellation/revision still wins.
            if (result.validated() != null) {
                runtime.verified(result.validated());
                result = new CreationResult(find(userId, result.response().id()), true);
            }
        }
        return result;
    }

    private CreationResult createTransaction(Long userId, CreateBoardingSessionRequest request, VerifiedBoardingStart start) {
        return transaction.execute(status -> {
            var user = users.findByIdForUpdate(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
            var duplicate = sessions.findRequestForUpdate(userId, request.clientRequestId());
            if (duplicate.isPresent()) {
                var session = duplicate.orElseThrow();
                expire(session);
                return new CreationResult(existing(session, request), false);
            }
            var current = sessions.findCurrentForUpdate(userId);
            current.ifPresent(this::expire);
            if (current.filter(session -> session.getStatus().isOpen()).isPresent()) {
                throw new ApiException(ErrorCode.BOARDING_SESSION_CONFLICT);
            }
            // Release an expired generated UNIQUE key before a new identity INSERT.
            sessions.flush();
            var original = start.metadata();
            var latest = metadata.read(original.routeId(), original.targetOccurrenceId(), original.boardingOccurrenceId());
            if (!latest.fingerprint().equals(original.fingerprint())) {
                throw new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED);
            }
            LocalDateTime now = now();
            if (clock.instant().isAfter(start.referenceExpiresAt())
                    || start.checkpoint().getObservedAt().isAfter(now)
                    || start.checkpoint().getObservedAt().isBefore(now.minus(BoardingCandidateReferenceStore.FRESHNESS))
                    || (start.checkpoint().getProviderDataTime() != null
                        && start.checkpoint().getProviderDataTime().isBefore(now.minus(BoardingCandidateReferenceStore.FRESHNESS)))) {
                throw new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED);
            }
            BoardingSession session = sessions.saveAndFlush(new BoardingSession(user, request.clientRequestId(),
                    request.fingerprint(), start.vehicleTrackingId(), start.vehicleNumber(), start.routeNumber(),
                    start.targetName(), start.traversal(), start.checkpoint(), now, now.plus(properties.getMaxLifetime()),
                    request.effectiveNotificationOffsetStops()));
            notifications.recordInitialPreparation(session);
            sessions.flush();
            return new CreationResult(response(session), true,
                    session.getStateReason() == null ? BoardingTrackingSnapshot.from(session) : null);
        });
    }

    private BoardingSessionResponse existing(BoardingSession session, CreateBoardingSessionRequest request) {
        if (!session.getRequestFingerprint().equals(session.getNotificationOffsetStops() == null
                && request.notificationOffsetStops() == null ? request.legacyFingerprint() : request.fingerprint())) {
            throw new ApiException(ErrorCode.BOARDING_REQUEST_CONFLICT);
        }
        return response(session);
    }

    public BoardingSessionResponse find(Long userId, Long id) {
        validId(id);
        return transaction.execute(status -> {
            authenticated(userId);
            BoardingSession session = owned(userId, id);
            expire(session);
            return response(session);
        });
    }

    public List<BoardingSessionResponse> findCurrent(Long userId) {
        return transaction.execute(status -> {
            authenticated(userId);
            var current = sessions.findCurrentForUpdate(userId);
            current.ifPresent(this::expire);
            return current.filter(session -> session.getStatus().isOpen()).stream()
                    .map(session -> response(session)).toList();
        });
    }

    public List<BoardingSessionResponse> findRequest(Long userId, String clientRequestId) {
        String key;
        try {
            key = BoardingSession.validateUuid(clientRequestId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return transaction.execute(status -> {
            authenticated(userId);
            var session = sessions.findRequestForUpdate(userId, key);
            session.ifPresent(this::expire);
            return session.stream().map(value -> response(value)).toList();
        });
    }

    public BoardingSessionResponse cancel(Long userId, Long id, long expectedRevision) {
        validId(id);
        if (expectedRevision < 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return transaction.execute(status -> {
            authenticated(userId);
            BoardingSession session = owned(userId, id);
            LocalDateTime now = now();
            expire(session);
            if (session.getStatus().isOpen() && session.getRevision() != expectedRevision) {
                throw new ApiException(ErrorCode.BOARDING_REVISION_CONFLICT);
            }
            runtime.committed(id, session.getRevision(), false);
            session.cancel(expectedRevision, now);
            notifications.terminatePending(session);
            sessions.flush();
            return response(session);
        });
    }

    public BoardingSessionResponse reconfirm(Long userId, Long id, ReconfirmBoardingSessionRequest request) {
        validId(id);
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        var expected = transaction.execute(status -> {
            authenticated(userId);
            var session = owned(userId, id);
            expire(session);
            response(session);
            return BoardingTrackingSnapshot.from(session);
        });
        requireReconfirmation(expected, request.expectedRevision());
        // Reuse reference ownership/expiry, current metadata and selected vehicle validation.
        // Passenger access does not affect validation of vehicle progress.
        var start = validator.validate(userId, request.candidateRef(), request.confirmedVehicleNumber());
        if (start.traversal().provider() != expected.provider()
                || !start.traversal().externalRouteId().equals(expected.routeId())
                || !java.util.Objects.equals(start.traversal().cityCode(), expected.cityCode())
                || !start.vehicleTrackingId().equals(expected.vehicleId())
                || !start.vehicleNumber().equals(expected.vehicleNumber())
                || !start.traversal().sameTrackingPath(expected.traversal())) {
            throw new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED);
        }
        var attempted = new AtomicReference<BoardingTrackingSnapshot>();
        BoardingTrackingSnapshot committed;
        try {
            committed = transaction.execute(status -> {
                var session = owned(userId, id);
                expire(session);
                response(session);
                var current = BoardingTrackingSnapshot.from(session);
                if (!current.status().isOpen()) {
                    return current;
                }
                requireReconfirmation(current, request.expectedRevision());
                if (!expected.matches(session)) {
                    throw new ApiException(ErrorCode.BOARDING_REVISION_CONFLICT);
                }
                var latest = metadata.read(start.metadata().routeId(), start.metadata().targetOccurrenceId(),
                        start.metadata().boardingOccurrenceId());
                if (!latest.fingerprint().equals(start.metadata().fingerprint()) || !fresh(start, now())) {
                    throw new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED);
                }
                session.reconfirm(request.expectedRevision(), start.checkpoint(), now());
                sessions.flush();
                var result = BoardingTrackingSnapshot.from(session);
                attempted.set(result);
                return result;
            });
        } catch (DataAccessException | TransactionException exception) {
            if (exception instanceof DataIntegrityViolationException || attempted.get() == null) {
                throw exception;
            }
            // Read-only reconciliation of this exact baseline. Rollback is never a reason to replay it.
            committed = transaction.execute(status -> {
                var current = owned(userId, id);
                var latest = metadata.read(start.metadata().routeId(), start.metadata().targetOccurrenceId(),
                        start.metadata().boardingOccurrenceId());
                if (!attempted.get().matches(current) || !latest.fingerprint().equals(start.metadata().fingerprint())
                        || !fresh(start, now()) || !now().isBefore(current.getExpiresAt())) {
                    throw exception;
                }
                return BoardingTrackingSnapshot.from(current);
            });
        }
        if (committed.status() != BoardingSessionStatus.TRACKING) {
            runtime.committed(committed.id(), committed.revision(), false);
            throw new ApiException(ErrorCode.BOARDING_RECONFIRM_REQUIRED);
        }
        runtime.verified(committed);
        return find(userId, id);
    }

    private static void requireReconfirmation(BoardingTrackingSnapshot session, long revision) {
        if (session.revision() != revision) {
            throw new ApiException(ErrorCode.BOARDING_REVISION_CONFLICT);
        }
        if (session.status() != BoardingSessionStatus.SUSPENDED) {
            throw new ApiException(ErrorCode.BOARDING_RECONFIRM_REQUIRED);
        }
    }

    private boolean fresh(VerifiedBoardingStart start, LocalDateTime now) {
        var checkpoint = start.checkpoint();
        return !clock.instant().isAfter(start.referenceExpiresAt())
                && !checkpoint.getObservedAt().isAfter(now)
                && !checkpoint.getObservedAt().isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS))
                && (checkpoint.getProviderDataTime() == null || (!checkpoint.getProviderDataTime().isAfter(now)
                    && !checkpoint.getProviderDataTime().isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS))));
    }

    private void expire(BoardingSession session) {
        session.expire(now());
        notifications.terminatePending(session);
        closeAfterCommit(session);
    }

    private void closeAfterCommit(BoardingSession session) {
        if (session.getStatus() != BoardingSessionStatus.TRACKING) {
            Long id = session.getId();
            long revision = session.getRevision();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runtime.committed(id, revision, false);
                }
            });
        }
    }

    private BoardingSessionResponse response(BoardingSession session) {
        closeAfterCommit(session);
        return runtime.response(session, now()).withPreparationStatus(notifications.preparationStatus(session.getId()));
    }

    private BoardingSession owned(Long userId, Long id) {
        return sessions.findOwnedForUpdate(id, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOARDING_SESSION_NOT_FOUND));
    }

    private void authenticated(Long userId) {
        if (userId == null || !users.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
        }
    }

    private static void validId(Long id) {
        if (id == null || id <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean knownUnique(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getSQLException().getErrorCode() == 1062) {
                String name = violation.getConstraintName();
                for (String known : List.of("uk_boarding_sessions_user_request", "uk_boarding_sessions_open_user")) {
                    if (known.equals(name) || (name != null && name.endsWith("." + known))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public record CreationResult(BoardingSessionResponse response, boolean created, BoardingTrackingSnapshot validated) {
        public CreationResult(BoardingSessionResponse response, boolean created) {
            this(response, created, null);
        }
    }
}
