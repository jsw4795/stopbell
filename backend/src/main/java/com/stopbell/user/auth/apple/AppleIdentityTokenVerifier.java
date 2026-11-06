package com.stopbell.user.auth.apple;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Verifies claims after an RS256-only, Apple JWKS-backed decoder verifies the signature. */
public final class AppleIdentityTokenVerifier {
    private static final String ISSUER = "https://appleid.apple.com";
    private final JwtDecoder decoder;
    private final String audience;
    private final Clock clock;

    public AppleIdentityTokenVerifier(JwtDecoder decoder, String audience, Clock clock) {
        if (audience == null || audience.isBlank()) throw new IllegalArgumentException("Apple audience required");
        this.decoder = decoder;
        this.audience = audience;
        this.clock = clock;
    }

    public String verify(String token, String nonce) {
        return verifyIdentity(token, nonce).subject();
    }

    public VerifiedIdentity verifyIdentity(String token, String nonce) {
        if (token == null || token.isBlank() || nonce == null || nonce.isBlank()) {
            throw new InvalidSocialCredentialException();
        }
        return verifyClaims(token, nonce);
    }

    public String verifyExchangeToken(String token) {
        if (token == null || token.isBlank()) throw new InvalidSocialCredentialException();
        return verifyClaims(token, null).subject();
    }

    private VerifiedIdentity verifyClaims(String token, String nonce) {
        final Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException | IllegalArgumentException failure) {
            throw new InvalidSocialCredentialException(failure);
        }
        Instant now = clock.instant();
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        List<String> audiences = jwt.getAudience();
        String subject = jwt.getSubject();
        if (!"RS256".equals(jwt.getHeaders().get("alg"))
                || !ISSUER.equals(jwt.getClaimAsString("iss"))
                || audiences == null || !audiences.contains(audience)
                || issuedAt == null || issuedAt.isAfter(now)
                || expiresAt == null || !expiresAt.isAfter(now)
                || (jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now))
                || (nonce != null && !nonce.equals(jwt.getClaimAsString("nonce")))
                || subject == null || subject.isBlank() || subject.length() > 255) {
            throw new InvalidSocialCredentialException();
        }
        return new VerifiedIdentity(subject, issuedAt);
    }
    public record VerifiedIdentity(String subject, Instant issuedAt) {
        @Override public String toString() { return "Apple verified identity [redacted]"; }
    }
}
