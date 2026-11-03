package com.stopbell.user.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.boarding.service.BoardingTrackingRuntime;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.user.auth.apple.AppleCredentialCipher;
import com.stopbell.user.auth.apple.AppleTokenRevocation;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.repository.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable intent -> HTTP without locks -> durable outcome -> existing atomic data deletion. */
@Service
public class AccountWithdrawalService {
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final AccountDeletionService deletion;
    private final ObjectProvider<AppleCredentialCipher> ciphers;
    private final ObjectProvider<AppleTokenRevocation> revocations;
    private final AlarmRepository alarms;
    private final BoardingSessionRepository sessions;
    private final NotificationDeliveryRepository deliveries;
    private final BoardingTrackingRuntime runtime;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AccountWithdrawalService(UserRepository users, JdbcTemplate jdbc, AccountDeletionService deletion,
            ObjectProvider<AppleCredentialCipher> ciphers, ObjectProvider<AppleTokenRevocation> revocations,
            AlarmRepository alarms, BoardingSessionRepository sessions, NotificationDeliveryRepository deliveries,
            BoardingTrackingRuntime runtime, Clock jwtClock, PlatformTransactionManager manager) {
        this.users = users;
        this.jdbc = jdbc;
        this.deletion = deletion;
        this.ciphers = ciphers;
        this.revocations = revocations;
        this.alarms = alarms;
        this.sessions = sessions;
        this.deliveries = deliveries;
        this.runtime = runtime;
        this.clock = jwtClock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Result withdraw(long userId) {
        return withdraw(userId, null);
    }

    public Result withdraw(long userId, String receiptHash) {
        // Explicitly refuse an ambient transaction: suspending it would still retain its DB locks during HTTP.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Account withdrawal requires no ambient transaction");
        }
        String state = transaction.execute(status -> prepare(userId, receiptHash));
        if (state == null) return Result.DELETED;
        if (state.equals("PENDING")) {
            AppleTokenRevocation.Outcome outcome = revoke(userId);
            state = transaction.execute(status -> record(userId, outcome));
            if (state == null) return Result.DELETED;
            if (state.equals("PENDING")) {
                if (outcome == AppleTokenRevocation.Outcome.CONFIGURATION_ERROR) {
                    throw new ApiException(ErrorCode.ACCOUNT_DELETION_CONFIGURATION_ERROR);
                }
                if (outcome == AppleTokenRevocation.Outcome.REJECTED) {
                    throw new ApiException(ErrorCode.ACCOUNT_DELETION_REJECTED);
                }
                return Result.PENDING;
            }
        }
        // REVOKED / MANUAL_REQUIRED survive a failed deletion or an uncertain commit response.
        deletion.delete(userId);
        return state.equals("MANUAL_REQUIRED") ? Result.DELETED_MANUAL_APPLE_REVOCATION_REQUIRED : Result.DELETED;
    }

    private String prepare(long userId, String receiptHash) {
        var user = users.findByIdForUpdate(userId).orElse(null);
        if (user == null) return null;
        if (receiptHash != null) {
            var owners = jdbc.queryForList("select pending_user_id from account_deletion_receipts where receipt_hash=? for update", Long.class, receiptHash);
            if (!owners.isEmpty()) {
                if (owners.getFirst() == null || owners.getFirst() != userId) {
                    throw new ApiException(ErrorCode.INVALID_REQUEST);
                }
            } else {
                jdbc.update("insert into account_deletion_receipts (receipt_hash,pending_user_id,state) values (?,?,'PENDING')", receiptHash, userId);
            }
        }
        var existing = jdbc.queryForList("select state from account_deletions where user_id=? for update", String.class, userId);
        if (!existing.isEmpty()) return existing.getFirst();
        boolean apple = user.getAuthProvider() == AuthProvider.APPLE
                || !jdbc.queryForList("select user_id from apple_credentials where user_id=?", Long.class, userId).isEmpty();
        String state = apple ? "PENDING" : "NOT_REQUIRED";
        jdbc.update("insert into account_deletions (user_id,state,requested_at,next_attempt_at) values (?,?,?,?)",
                userId, state, now(), now().plusMinutes(1));
        // Same order as deletion: User -> Alarm -> Session -> Delivery. Stop decisions before HTTP.
        for (Long id : jdbc.queryForList("select id from alarms where user_id=? order by id", Long.class, userId)) {
            alarms.findByIdForUpdate(id).ifPresent(alarm -> alarm.deactivate());
        }
        for (Long id : jdbc.queryForList("select id from boarding_sessions where user_id=? order by id", Long.class, userId)) {
            sessions.findOwnedForUpdate(id, userId).ifPresent(session -> {
                session.cancel(session.getRevision(), now());
                long revision = session.getRevision();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { runtime.committed(id, revision, false); }
                });
            });
        }
        for (Long id : jdbc.queryForList("""
                select d.id from notification_deliveries d join notification_events e on e.id=d.notification_event_id
                where e.user_id=? order by d.id
                """, Long.class, userId)) {
            deliveries.findByIdForUpdate(id).ifPresent(delivery -> delivery.terminateDispatch(now()));
        }
        return state;
    }

    private AppleTokenRevocation.Outcome revoke(long userId) {
        // User/credential mutations are fenced by durable intent, so this snapshot cannot be replaced by login.
        var credentials = jdbc.queryForList("select encrypted_refresh_token from apple_credentials where user_id=?",
                String.class, userId);
        if (credentials.isEmpty()) return AppleTokenRevocation.Outcome.CREDENTIAL_UNAVAILABLE;
        var cipher = ciphers.getIfAvailable();
        var revocation = revocations.getIfAvailable();
        if (cipher == null || revocation == null) return AppleTokenRevocation.Outcome.CONFIGURATION_ERROR;
        final String token;
        try { token = cipher.decrypt(userId, credentials.getFirst()); }
        catch (RuntimeException failure) { return AppleTokenRevocation.Outcome.CREDENTIAL_UNAVAILABLE; }
        return revocation.revoke(token);
    }

    private String record(long userId, AppleTokenRevocation.Outcome outcome) {
        if (users.findByIdForUpdate(userId).isEmpty()) return null;
        var states = jdbc.queryForList("select state from account_deletions where user_id=? for update", String.class, userId);
        String state = states.getFirst();
        if (!state.equals("PENDING")) return state; // Concurrent retries cannot downgrade a confirmed outcome.
        state = switch (outcome) {
            case CREDENTIAL_UNAVAILABLE -> "MANUAL_REQUIRED";
            case REVOKED -> "REVOKED";
            default -> "PENDING";
        };
        jdbc.update("update account_deletions set state=?,last_error=?,next_attempt_at=? where user_id=?",
                state, outcome == AppleTokenRevocation.Outcome.REVOKED ? null : outcome.name(), now().plusMinutes(1), userId);
        return state;
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    public enum Result { DELETED, DELETED_MANUAL_APPLE_REVOCATION_REQUIRED, PENDING }
}
