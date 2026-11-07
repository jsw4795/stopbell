package com.stopbell.user.auth.apple;

import java.time.Instant;

import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Verifies Apple credentials before the application login transaction begins. */
public final class AppleAuthentication {
    private final AppleNonceStore nonces;
    private final AppleIdentityBoundary boundary;
    private final AppleIdentityTokenVerifier verifier;
    private final AppleCodeExchange exchange;

    public AppleAuthentication(AppleNonceStore nonces, AppleIdentityTokenVerifier verifier, AppleCodeExchange exchange, AppleIdentityBoundary boundary) {
        this.nonces = nonces;
        this.boundary = boundary;
        this.verifier = verifier;
        this.exchange = exchange;
    }

    public String issueNonce() { return nonces.issue(boundary.challengeGeneration()); }

    public Result authenticate(String identityToken, String authorizationCode, String nonce) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Apple authentication requires no enclosing transaction");
        }
        long generation = nonces.consume(nonce);
        var identity = verifier.verifyIdentity(identityToken, nonce);
        String subject = identity.subject();
        AppleCodeExchange.Tokens tokens = exchange.exchange(authorizationCode);
        if (!subject.equals(verifier.verifyExchangeToken(tokens.idToken()))) {
            throw new InvalidSocialCredentialException();
        }
        return new Result(subject, tokens.refreshToken(), identity.issuedAt(), generation);
    }

    public static final class Result {
        private final String subject;
        private final String refreshToken;
        private final Instant identityIssuedAt;
        private final long challengeGeneration;
        Result(String subject, String refreshToken, Instant identityIssuedAt, long challengeGeneration) {
            this.subject = subject; this.refreshToken = refreshToken; this.identityIssuedAt = identityIssuedAt; this.challengeGeneration = challengeGeneration;
        }
        public long challengeGeneration() { return challengeGeneration; }
        public String subject() { return subject; }
        public String refreshToken() { return refreshToken; }
        public Instant identityIssuedAt() { return identityIssuedAt; }
        @Override public String toString() { return "Apple authentication result [redacted]"; }
    }
}
