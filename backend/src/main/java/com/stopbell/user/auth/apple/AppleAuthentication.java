package com.stopbell.user.auth.apple;

import com.stopbell.user.auth.exception.InvalidSocialCredentialException;

/** Authentication building block; deliberately not exposed as an endpoint. */
public final class AppleAuthentication {
    private final AppleNonceStore nonces;
    private final AppleIdentityTokenVerifier verifier;
    private final AppleCodeExchange exchange;

    public AppleAuthentication(AppleNonceStore nonces, AppleIdentityTokenVerifier verifier, AppleCodeExchange exchange) {
        this.nonces = nonces;
        this.verifier = verifier;
        this.exchange = exchange;
    }

    public String issueNonce() { return nonces.issue(); }

    public Result authenticate(String identityToken, String authorizationCode, String nonce) {
        nonces.consume(nonce);
        String subject = verifier.verify(identityToken, nonce);
        AppleCodeExchange.Tokens tokens = exchange.exchange(authorizationCode);
        if (!subject.equals(verifier.verifyExchangeToken(tokens.idToken()))) {
            throw new InvalidSocialCredentialException();
        }
        return new Result(subject, tokens.refreshToken());
    }

    public static final class Result {
        private final String subject;
        private final String refreshToken;
        Result(String subject, String refreshToken) { this.subject = subject; this.refreshToken = refreshToken; }
        public String subject() { return subject; }
        public String refreshToken() { return refreshToken; }
        @Override public String toString() { return "Apple authentication result [redacted]"; }
    }
}
