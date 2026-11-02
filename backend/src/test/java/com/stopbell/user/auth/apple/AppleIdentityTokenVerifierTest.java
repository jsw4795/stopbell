package com.stopbell.user.auth.apple;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppleIdentityTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private Jwt token(Consumer<Jwt.Builder> changes) {
        Jwt.Builder builder = Jwt.withTokenValue("fixture")
                .header("alg", "RS256")
                .issuer("https://appleid.apple.com")
                .audience(List.of("com.stopbell.stopbell"))
                .issuedAt(NOW.minusSeconds(30))
                .expiresAt(NOW.plusSeconds(300))
                .subject("apple-user")
                .claim("nonce", "challenge");
        changes.accept(builder);
        return builder.build();
    }

    private AppleIdentityTokenVerifier verifier(Jwt jwt) {
        return new AppleIdentityTokenVerifier(ignored -> jwt, "com.stopbell.stopbell", CLOCK);
    }

    @Test
    void acceptsMatchingClaims() {
        assertThat(verifier(token(ignored -> {})).verify("signed", "challenge")).isEqualTo("apple-user");
    }

    @Test
    void rejectsWrongClaimsAndDecoderFailures() {
        for (Jwt jwt : List.of(
                token(b -> b.header("alg", "HS256")),
                token(b -> b.issuer("https://example.com")),
                token(b -> b.audience(List.of("other"))),
                token(b -> b.issuedAt(NOW.plusSeconds(120))),
                token(b -> b.expiresAt(NOW)),
                token(b -> b.claim("nonce", "other")),
                token(b -> b.subject("")))) {
            assertThatThrownBy(() -> verifier(jwt).verify("signed", "challenge"))
                    .isInstanceOf(InvalidSocialCredentialException.class);
        }
        var failure = new AppleIdentityTokenVerifier(value -> { throw new JwtException("bad signature"); },
                "com.stopbell.stopbell", CLOCK);
        assertThatThrownBy(() -> failure.verify("signed", "challenge"))
                .isInstanceOf(InvalidSocialCredentialException.class);
    }
}
