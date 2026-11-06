package com.stopbell.user.auth.apple;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serializes Apple login and verified revocation events by subject, even without a User row. */
@Service
public class AppleIdentityBoundary {
    private final JdbcTemplate jdbc;

    public AppleIdentityBoundary(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void acceptLogin(String subject, Instant issuedAt) {
        if (issuedAt == null) throw new InvalidSocialCredentialException();
        byte[] key = lock(subject);
        var row = jdbc.queryForObject("select latest_identity_issued_at,latest_revocation_at from apple_identity_boundaries where subject_hash=? for update",
                (rs, index) -> new Times(rs.getObject(1, LocalDateTime.class), rs.getObject(2, LocalDateTime.class)), key);
        LocalDateTime previous = row.identity();
        LocalDateTime revocation = row.revocation();
        LocalDateTime time = utc(issuedAt.truncatedTo(ChronoUnit.SECONDS));
        if ((previous != null && !time.isAfter(previous))
                || (revocation != null && !time.isAfter(revocation.truncatedTo(ChronoUnit.SECONDS)))) {
            throw new InvalidSocialCredentialException();
        }
        jdbc.update("update apple_identity_boundaries set latest_identity_issued_at=? where subject_hash=?", time, key);
    }

    /** Caller must hold the transaction through event storage and any future deletion decision. */
    byte[] lock(String subject) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Apple identity boundary requires a transaction");
        byte[] hash = hash(subject);
        jdbc.update("insert into apple_identity_boundaries (subject_hash) values (?) on duplicate key update subject_hash=subject_hash", hash);
        jdbc.queryForObject("select subject_hash from apple_identity_boundaries where subject_hash=? for update", byte[].class, hash);
        return hash;
    }

    void recordRevocation(byte[] hash, Instant eventTime) {
        LocalDateTime time = utc(eventTime);
        jdbc.update("update apple_identity_boundaries set latest_revocation_at=greatest(coalesce(latest_revocation_at,?),?) where subject_hash=?", time, time, hash);
    }

    /** Temporal assessment only: caller must hold this transaction while checking the exact APPLE user and deleting it. */
    public PendingAssessment lockPendingEvent(byte[] jtiHash) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Apple identity boundary requires a transaction");
        var subjects = jdbc.query("select apple_subject from apple_notification_events where jti_hash=? and state='PENDING' and event_type in ('consent-revoked','account-deleted')",
                (rs, index) -> rs.getString(1), jtiHash);
        if (subjects.isEmpty()) return null;
        String subject = subjects.getFirst();
        byte[] key = lock(subject);
        var eventTimes = jdbc.query("select event_time from apple_notification_events where jti_hash=? and state='PENDING' and apple_subject=? and event_type in ('consent-revoked','account-deleted') for update",
                (rs, index) -> rs.getObject(1, LocalDateTime.class), jtiHash, subject);
        if (eventTimes.isEmpty()) return null;
        LocalDateTime eventTime = eventTimes.getFirst().truncatedTo(ChronoUnit.SECONDS);
        var times = jdbc.queryForObject("select latest_identity_issued_at,latest_revocation_at from apple_identity_boundaries where subject_hash=? for update",
                (rs, index) -> new Times(rs.getObject(1, LocalDateTime.class), rs.getObject(2, LocalDateTime.class)), key);
        LocalDateTime issued = times.identity();
        TemporalDecision decision = issued == null || issued.truncatedTo(ChronoUnit.SECONDS).equals(eventTime)
                ? TemporalDecision.REVIEW_REQUIRED
                : issued.isAfter(eventTime) ? TemporalDecision.NEWER_AUTHENTICATION : TemporalDecision.EVENT_AFTER_AUTHENTICATION;
        return new PendingAssessment(subject, decision);
    }

    public enum TemporalDecision { REVIEW_REQUIRED, NEWER_AUTHENTICATION, EVENT_AFTER_AUTHENTICATION }
    public record PendingAssessment(String subject, TemporalDecision decision) {
        @Override public String toString() { return "Apple pending assessment [redacted]"; }
    }

    private record Times(LocalDateTime identity, LocalDateTime revocation) { }

    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
