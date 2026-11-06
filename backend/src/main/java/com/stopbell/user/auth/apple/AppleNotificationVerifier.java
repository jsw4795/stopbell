package com.stopbell.user.auth.apple;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.Map;
import java.util.Set;

import com.nimbusds.jwt.JWTClaimsSet;
import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import org.springframework.security.oauth2.jwt.JwtException;

/** Notification claims have a different contract from Apple login ID tokens. */
public final class AppleNotificationVerifier {
    private static final Set<String> TYPES = Set.of("consent-revoked", "account-deleted", "email-disabled", "email-enabled");
    private final AppleJwksDecoder decoder;
    private final String audience;
    private final Clock clock;

    public AppleNotificationVerifier(AppleJwksDecoder decoder, String audience, Clock clock) {
        if (audience == null || audience.isBlank() || audience.length() > 255) throw new IllegalStateException("Apple notification App ID is required");
        this.decoder = decoder;
        this.audience = audience;
        this.clock = clock;
    }

    public Event verify(String token) {
        try {
            JWTClaimsSet claims = decoder.verifySignedClaims(token);
            Instant now = clock.instant();
            var issued = claims.getIssueTime();
            var expiry = claims.getExpirationTime();
            var notBefore = claims.getNotBeforeTime();
            if (!"https://appleid.apple.com".equals(claims.getIssuer())
                    || claims.getAudience().size() != 1 || !audience.equals(claims.getAudience().getFirst())
                    || issued == null || issued.toInstant().isAfter(now) || issued.toInstant().isBefore(Instant.EPOCH)
                    || (claims.getClaim("exp") != null && (expiry == null || !expiry.toInstant().isAfter(now)))
                    || (claims.getClaim("nbf") != null && (notBefore == null || notBefore.toInstant().isAfter(now)))) throw invalid();
            String jti = claims.getJWTID();
            Object raw = claims.getClaim("events");
            // Some Apple deliveries encode the events object as a JSON string.
            if (raw instanceof String string) raw = com.nimbusds.jose.util.JSONObjectUtils.parse(string);
            if (!(raw instanceof Map<?, ?> events)) throw invalid();
            Object type = events.get("type"), subject = events.get("sub"), time = events.get("event_time");
            if (!valid(jti, 255) || !(type instanceof String t) || !TYPES.contains(t)
                    || !(subject instanceof String s) || !valid(s, 255)
                    || !(time instanceof Number n) || n.doubleValue() != n.longValue()) throw invalid();
            long seconds = n.longValue();
            // Accept the seconds documented by Apple and the millisecond form observed in deployments.
            Instant eventTime = seconds >= 100_000_000_000L ? Instant.ofEpochMilli(seconds) : Instant.ofEpochSecond(seconds);
            if (eventTime.isBefore(Instant.EPOCH) || eventTime.isAfter(now)) throw invalid();
            return new Event(jti, t, s, eventTime);
        } catch (ParseException | JwtException | IllegalArgumentException | DateTimeException failure) {
            throw invalid();
        }
    }

    private static boolean valid(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static InvalidSocialCredentialException invalid() { return new InvalidSocialCredentialException(); }
    public record Event(String jti, String type, String subject, Instant eventTime) {
        @Override public String toString() { return "Apple notification [redacted]"; }
    }
}
