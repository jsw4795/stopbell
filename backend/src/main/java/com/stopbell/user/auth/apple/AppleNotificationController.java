package com.stopbell.user.auth.apple;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Clock;

import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "apple.notifications", name = "enabled", havingValue = "true")
public class AppleNotificationController {
    private final AppleNotificationVerifier verifier;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    AppleNotificationController(AppleNotificationVerifier verifier, JdbcTemplate jdbc, Clock jwtClock) {
        this.verifier = verifier;
        this.jdbc = jdbc;
        this.clock = jwtClock;
    }
    @PostMapping("/auth/apple/notifications")
    public ResponseEntity<Void> receive(@RequestBody Request request) {
        if (request == null) throw new InvalidSocialCredentialException();
        var event = verifier.verify(request.payload());
        boolean pending = event.type().equals("consent-revoked") || event.type().equals("account-deleted");
        try {
            jdbc.update("""
                    insert into apple_notification_events (jti_hash,event_type,apple_subject,event_time,received_at,state)
                    values (?,?,?,?,?,?) on duplicate key update jti_hash=jti_hash
                    """, hash(event.jti()), event.type(), pending ? event.subject() : null,
                    LocalDateTime.ofInstant(event.eventTime(), ZoneOffset.UTC), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), pending ? "PENDING" : "IGNORED");
            jdbc.update("delete from apple_notification_events where state='IGNORED' and received_at <= ?",
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(30));
        } catch (DataAccessException failure) {
            // Do not expose database diagnostics containing user identifiers.
            throw new IllegalStateException("Apple notification storage failed");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }
    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    public record Request(String payload) {
        @Override public String toString() { return "Apple notification request [redacted]"; }
    }
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "apple.notifications", name = "enabled", havingValue = "true")
    static class Config {
        @Bean AppleNotificationVerifier appleNotificationVerifier(@Value("${apple.notifications.client-id:}") String clientId, Clock jwtClock) {
            return new AppleNotificationVerifier(new AppleJwksDecoder(), clientId, jwtClock);
        }
    }
}
