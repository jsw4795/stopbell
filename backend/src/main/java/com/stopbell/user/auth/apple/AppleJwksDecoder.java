package com.stopbell.user.auth.apple;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.text.ParseException;
import java.util.Date;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.stopbell.user.auth.exception.SocialIdentityVerificationException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Fetches a bounded Apple JWK set and verifies the compact JWS before exposing claims. */
public final class AppleJwksDecoder implements JwtDecoder {
    private static final int MAX_JWKS_BYTES = 65536;
    private static final int MAX_JWT_CHARS = 16384;
    private final URI endpoint;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public AppleJwksDecoder() {
        this(URI.create("https://appleid.apple.com/auth/keys"), 2000, 5000);
    }

    AppleJwksDecoder(URI endpoint, int connectTimeoutMillis, int readTimeoutMillis) {
        this.endpoint = endpoint;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public Jwt decode(String value) {
        if (value == null || value.length() > MAX_JWT_CHARS) throw invalid();
        final SignedJWT token;
        try {
            token = SignedJWT.parse(value);
        } catch (ParseException | IllegalArgumentException failure) {
            throw invalid();
        }
        String kid = token.getHeader().getKeyID();
        if (!JWSAlgorithm.RS256.equals(token.getHeader().getAlgorithm()) || kid == null || kid.isBlank()
                || kid.length() > 128 || token.getHeader().getCriticalParams() != null) throw invalid();
        JWKSet keys = fetchKeys();
        JWK selected = keys.getKeyByKeyId(kid);
        if (!(selected instanceof RSAKey rsa) || rsa.isPrivate()
                || (rsa.getKeyUse() != null && !KeyUse.SIGNATURE.equals(rsa.getKeyUse()))
                || (rsa.getAlgorithm() != null && !JWSAlgorithm.RS256.equals(rsa.getAlgorithm()))) throw invalid();
        try {
            if (!token.verify(new RSASSAVerifier(rsa.toRSAPublicKey()))) throw invalid();
            JWTClaimsSet claims = token.getJWTClaimsSet();
            Object subject = claims.getClaim("sub");
            Object issuer = claims.getClaim("iss");
            Object nonce = claims.getClaim("nonce");
            Date issued = claims.getIssueTime();
            Date expires = claims.getExpirationTime();
            Date notBefore = claims.getNotBeforeTime();
            if (!(subject instanceof String) || !(issuer instanceof String)
                    || (nonce != null && !(nonce instanceof String))
                    || issued == null || expires == null) throw invalid();
            var builder = Jwt.withTokenValue(value).header("alg", "RS256").header("kid", kid)
                    .subject((String) subject).issuer((String) issuer).audience(claims.getAudience())
                    .issuedAt(issued.toInstant()).expiresAt(expires.toInstant());
            if (nonce != null) builder.claim("nonce", nonce);
            if (notBefore != null) builder.notBefore(notBefore.toInstant());
            return builder.build();
        } catch (ParseException | com.nimbusds.jose.JOSEException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private JWKSet fetchKeys() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) endpoint.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            if (connection.getResponseCode() != 200) throw new IOException("Apple JWKS response unavailable");
            if (connection.getContentLengthLong() > MAX_JWKS_BYTES) throw new IOException("Apple JWKS too large");
            try (var input = connection.getInputStream()) {
                byte[] data = AppleHttpBody.read(connection, input, MAX_JWKS_BYTES, readTimeoutMillis);
                return JWKSet.parse(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException | ParseException failure) {
            throw new SocialIdentityVerificationException(new IllegalStateException("Apple JWKS unavailable"));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static BadJwtException invalid() {
        return new BadJwtException("Invalid Apple identity token");
    }
}
