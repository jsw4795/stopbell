package com.stopbell.user.auth.apple;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.auth.exception.SocialIdentityVerificationException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppleJwksDecoderTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    private String token(RSAKey key, String kid) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://appleid.apple.com")
                .audience("app").subject("apple-user").issueTime(Date.from(NOW.minusSeconds(1)))
                .expirationTime(Date.from(NOW.plusSeconds(60))).claim("nonce", "nonce").build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("first").build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void verifiesSignatureAndFindsRotatedKeysUsingMockJwks() throws Exception {
        RSAKey first = new RSAKeyGenerator(2048).keyID("first").generate();
        RSAKey second = new RSAKeyGenerator(2048).keyID("second").generate();
        AtomicReference<String> body = new AtomicReference<>(new JWKSet(first.toPublicJWK()).toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/keys");
            var decoder = new AppleJwksDecoder(uri, 1000, 1000);
            var verifier = new AppleIdentityTokenVerifier(decoder, "app", Clock.fixed(NOW, ZoneOffset.UTC));
            assertThat(verifier.verify(token(first, "first"), "nonce")).isEqualTo("apple-user");
            assertThatThrownBy(() -> verifier.verify(token(second, "first"), "nonce"))
                    .isInstanceOf(InvalidSocialCredentialException.class);
            assertThatThrownBy(() -> verifier.verify(token(first, "missing"), "nonce"))
                    .isInstanceOf(InvalidSocialCredentialException.class);
            assertThatThrownBy(() -> verifier.verify(token(first, "first"), "wrong"))
                    .isInstanceOf(InvalidSocialCredentialException.class);
            var valid = new JWTClaimsSet.Builder().issuer("https://appleid.apple.com").audience("app")
                    .subject("apple-user").issueTime(Date.from(NOW.minusSeconds(1)))
                    .expirationTime(Date.from(NOW.plusSeconds(60))).claim("nonce", "nonce").build();
            for (var claims : java.util.List.of(
                    new JWTClaimsSet.Builder(valid).issuer("https://wrong.example").build(),
                    new JWTClaimsSet.Builder(valid).audience("other").build(),
                    new JWTClaimsSet.Builder(valid).expirationTime(Date.from(NOW)).build(),
                    new JWTClaimsSet.Builder(valid).issueTime(Date.from(NOW.plusSeconds(1))).build())) {
                String signed = sign(first, claims);
                assertThatThrownBy(() -> verifier.verify(signed, "nonce"))
                        .isInstanceOf(InvalidSocialCredentialException.class);
            }
            var hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("first").build(),
                    new JWTClaimsSet.Builder().subject("apple-user").build());
            hs.sign(new MACSigner(new byte[32]));
            assertThatThrownBy(() -> verifier.verify(hs.serialize(), "nonce"))
                    .isInstanceOf(InvalidSocialCredentialException.class);
            body.set(new JWKSet(second.toPublicJWK()).toString());
            assertThat(verifier.verify(token(second, "second"), "nonce")).isEqualTo("apple-user");
            body.set("x".repeat(65537));
            assertThatThrownBy(() -> verifier.verify(token(second, "second"), "nonce"))
                    .isInstanceOf(SocialIdentityVerificationException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unavailableJwksIsNotAnAuthenticationFailure() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("key").generate();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", exchange -> {
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { exchange.sendResponseHeaders(503, -1); } finally { exchange.close(); }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/keys");
            var verifier = new AppleIdentityTokenVerifier(new AppleJwksDecoder(uri, 1000, 30), "app",
                    Clock.fixed(NOW, ZoneOffset.UTC));
            assertThatThrownBy(() -> verifier.verify(token(key, "key"), "nonce"))
                    .isInstanceOf(SocialIdentityVerificationException.class);
        } finally { server.stop(0); }
    }

    @Test
    void incompleteJwksBodyTimesOut() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("key").generate();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write('{');
                out.flush();
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        try {
            var decoder = new AppleJwksDecoder(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/keys"), 1000, 30);
            var verifier = new AppleIdentityTokenVerifier(decoder, "app", Clock.fixed(NOW, ZoneOffset.UTC));
            assertThatThrownBy(() -> verifier.verify(token(key, "key"), "nonce"))
                    .isInstanceOf(SocialIdentityVerificationException.class);
        } finally { server.stop(0); }
    }
}
