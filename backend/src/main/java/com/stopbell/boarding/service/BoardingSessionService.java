package com.stopbell.boarding.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

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

    public BoardingSessionService(BoardingSessionRepository sessions, UserRepository users,
            BoardingSessionStartValidator validator, BoardingCandidateMetadataService metadata,
            BoardingSessionProperties properties, PlatformTransactionManager manager,
            @Qualifier("transitMetadataClock") Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.validator = validator;
        this.metadata = metadata;
        this.properties = properties;
        this.clock = clock;
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
                session.expire(now());
                return existing(session, request);
            });
        });
        if (existing.isPresent()) {
            return new CreationResult(existing.orElseThrow(), false);
        }
        // All Provider I/O occurs here, before either User or Session lock is acquired.
        VerifiedBoardingStart start = validator.validate(userId, request.candidateRef(), request.confirmedVehicleNumber());
        try {
            return createTransaction(userId, request, start);
        } catch (DataAccessException | TransactionException exception) {
            if (exception instanceof DataIntegrityViolationException integrity && !knownUnique(integrity)) {
                throw exception;
            }
            // One bounded reconciliation in a fresh transaction/persistence context. Use the
            // original request/evidence; never refetch a different vehicle or blindly upsert.
            return createTransaction(userId, request, start);
        }
    }

    private CreationResult createTransaction(Long userId, CreateBoardingSessionRequest request, VerifiedBoardingStart start) {
        return transaction.execute(status -> {
            var user = users.findByIdForUpdate(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
            var duplicate = sessions.findRequestForUpdate(userId, request.clientRequestId());
            if (duplicate.isPresent()) {
                var session = duplicate.orElseThrow();
                session.expire(now());
                return new CreationResult(existing(session, request), false);
            }
            var current = sessions.findCurrentForUpdate(userId);
            current.ifPresent(session -> session.expire(now()));
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
                    || !start.vehicleNumber().equals(request.confirmedVehicleNumber())
                    || start.checkpoint().getObservedAt().isAfter(now)
                    || start.checkpoint().getObservedAt().isBefore(now.minus(BoardingCandidateReferenceStore.FRESHNESS))
                    || (start.checkpoint().getProviderDataTime() != null
                        && start.checkpoint().getProviderDataTime().isBefore(now.minus(BoardingCandidateReferenceStore.FRESHNESS)))) {
                throw new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED);
            }
            BoardingSession session = sessions.saveAndFlush(new BoardingSession(user, request.clientRequestId(),
                    request.fingerprint(), start.vehicleTrackingId(), start.vehicleNumber(), start.routeNumber(),
                    start.targetName(), start.traversal(), start.checkpoint(), now, now.plus(properties.getMaxLifetime())));
            return new CreationResult(BoardingSessionResponse.from(session), true);
        });
    }

    private BoardingSessionResponse existing(BoardingSession session, CreateBoardingSessionRequest request) {
        if (!session.getRequestFingerprint().equals(request.fingerprint())) {
            throw new ApiException(ErrorCode.BOARDING_REQUEST_CONFLICT);
        }
        return BoardingSessionResponse.from(session);
    }

    public BoardingSessionResponse find(Long userId, Long id) {
        validId(id);
        return transaction.execute(status -> {
            authenticated(userId);
            BoardingSession session = owned(userId, id);
            session.expire(now());
            return BoardingSessionResponse.from(session);
        });
    }

    public List<BoardingSessionResponse> findCurrent(Long userId) {
        return transaction.execute(status -> {
            authenticated(userId);
            var current = sessions.findCurrentForUpdate(userId);
            current.ifPresent(session -> session.expire(now()));
            return current.filter(session -> session.getStatus().isOpen()).stream()
                    .map(BoardingSessionResponse::from).toList();
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
            session.ifPresent(value -> value.expire(now()));
            return session.stream().map(BoardingSessionResponse::from).toList();
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
            session.expire(now);
            if (session.getStatus().isOpen() && session.getRevision() != expectedRevision) {
                throw new ApiException(ErrorCode.BOARDING_REVISION_CONFLICT);
            }
            // TASK-906 can terminate this aggregate's PENDING deliveries in this transaction.
            session.cancel(expectedRevision, now);
            sessions.flush();
            return BoardingSessionResponse.from(session);
        });
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

    public record CreationResult(BoardingSessionResponse response, boolean created) {
    }
}
