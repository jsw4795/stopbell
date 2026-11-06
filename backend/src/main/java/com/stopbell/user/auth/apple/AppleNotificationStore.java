package com.stopbell.user.auth.apple;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores only verified notifications; boundary and event are committed atomically. */
@Service
public class AppleNotificationStore {
    private final JdbcTemplate jdbc;
    private final AppleIdentityBoundary boundary;
    private final Clock clock;

    public AppleNotificationStore(JdbcTemplate jdbc, AppleIdentityBoundary boundary, Clock jwtClock) {
        this.jdbc = jdbc;
        this.boundary = boundary;
        this.clock = jwtClock;
    }

    @Transactional
    public void store(AppleNotificationVerifier.Event event) {
        boolean pending = event.type().equals("consent-revoked") || event.type().equals("account-deleted");
        byte[] boundaryKey = pending ? boundary.lock(event.subject()) : null;
        byte[] jtiHash = hash(event.jti());
        jdbc.update("""
                insert into apple_notification_events (jti_hash,event_type,apple_subject,event_time,received_at,state)
                values (?,?,?,?,?,?) on duplicate key update jti_hash=jti_hash
                """, jtiHash, event.type(), pending ? event.subject() : null,
                LocalDateTime.ofInstant(event.eventTime(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), pending ? "PENDING" : "IGNORED");
        if (pending) {
            var saved = jdbc.queryForObject("select event_type,apple_subject,event_time,state from apple_notification_events where jti_hash=? for update",
                    (rs, index) -> new SavedEvent(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDateTime.class), rs.getString(4)), jtiHash);
            if (!event.type().equals(saved.type()) || (saved.subject() != null && !event.subject().equals(saved.subject()))
                    || !LocalDateTime.ofInstant(event.eventTime(), ZoneOffset.UTC).equals(saved.time())) {
                throw new IllegalStateException("Conflicting Apple notification identity");
            }
            if (saved.state().equals("PENDING") || saved.state().equals("REVIEW_REQUIRED")) {
                boundary.recordRevocation(boundaryKey, event.eventTime());
            }
        }
        jdbc.update("delete from apple_notification_events where state='IGNORED' and received_at <= ?",
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(30));
    }

    private record SavedEvent(String type, String subject, LocalDateTime time, String state) { }

    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
