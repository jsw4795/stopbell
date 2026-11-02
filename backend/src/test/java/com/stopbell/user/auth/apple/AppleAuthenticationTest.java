package com.stopbell.user.auth.apple;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
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

class AppleAuthenticationTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private String sign(RSAKey key, String subject, String nonce) throws Exception {
        var builder = new JWTClaimsSet.Builder().issuer("https://appleid.apple.com")
                .audience("app").subject(subject).issueTime(Date.from(NOW.minusSeconds(1)))
                .expirationTime(Date.from(NOW.plusSeconds(60)));
        if (nonce != null) builder.claim("nonce", nonce);
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key").build(), builder.build());
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    @Test
    void exchangesSingleUseCodeAndChecksSignedSubjectsAndNonce() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        var secret = new AppleClientSecret("app", "team", "keyid", pem, CLOCK);
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("key").generate();
        AtomicBoolean used = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", request -> {
            byte[] bytes = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, bytes.length);
            try (var out = request.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/token", request -> {
            Map<String, String> form = new HashMap<>();
            for (String field : new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                String[] parts = field.split("=", 2);
                form.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            String response;
            int status;
            try {
                var clientJwt = SignedJWT.parse(form.get("client_secret"));
                if (!clientJwt.verify(new ECDSAVerifier((ECPublicKey) pair.getPublic()))
                        || !"team".equals(clientJwt.getJWTClaimsSet().getIssuer())
                        || !"app".equals(clientJwt.getJWTClaimsSet().getSubject())
                        || !clientJwt.getJWTClaimsSet().getAudience().equals(java.util.List.of("https://appleid.apple.com"))
                        || !clientJwt.getJWTClaimsSet().getExpirationTime().toInstant().equals(NOW.plusSeconds(300))
                        || !"keyid".equals(clientJwt.getHeader().getKeyID())
                        || !"authorization_code".equals(form.get("grant_type"))) throw new AssertionError();
                if ("once".equals(form.get("code")) && !used.getAndSet(true)) {
                    status = 200;
                    response = "{\"id_token\":\"" + sign(signingKey, "apple-user", null)
                            + "\",\"refresh_token\":\"fixture-refresh\"}";
                } else if ("mismatch".equals(form.get("code"))) {
                    status = 200;
                    response = "{\"id_token\":\"" + sign(signingKey, "another-user", null)
                            + "\",\"refresh_token\":\"fixture-refresh\"}";
                } else if ("configuration".equals(form.get("code"))) {
                    status = 400;
                    response = "{\"error\":\"invalid_client\"}";
                } else if ("outage".equals(form.get("code"))) {
                    status = 503;
                    response = "{}";
                } else {
                    status = 400;
                    response = "{\"error\":\"invalid_grant\"}";
                }
            } catch (Exception e) { throw new RuntimeException(e); }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(status, bytes.length);
            try (var out = request.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            var decoder = new AppleJwksDecoder(URI.create(origin + "/keys"), 1000, 1000);
            var verifier = new AppleIdentityTokenVerifier(decoder, "app", CLOCK);
            var exchange = new AppleCodeExchange(URI.create(origin + "/token"), "app", secret, 1000, 1000);
            var authentication = new AppleAuthentication(new AppleNonceStore(CLOCK), verifier, exchange);
            String nonce = authentication.issueNonce();
            String initial = sign(signingKey, "apple-user", nonce);
            var result = authentication.authenticate(initial, "once", nonce);
            assertThat(result.subject()).isEqualTo("apple-user");
            assertThat(result.refreshToken()).isEqualTo("fixture-refresh");
            assertThat(result.toString()).doesNotContain("fixture-refresh", "apple-user");
            assertThatThrownBy(() -> authentication.authenticate(initial, "once", nonce))
                    .isInstanceOf(InvalidSocialCredentialException.class);
            String next = authentication.issueNonce();
            String nextToken = sign(signingKey, "apple-user", next);
            assertThatThrownBy(() -> authentication.authenticate(nextToken, "once", next))
                    .isInstanceOf(InvalidSocialCredentialException.class);
            String mismatch = authentication.issueNonce();
            String mismatchToken = sign(signingKey, "apple-user", mismatch);
            assertThatThrownBy(() -> authentication.authenticate(mismatchToken, "mismatch", mismatch))
                    .isInstanceOf(InvalidSocialCredentialException.class);
            String wrongNonce = authentication.issueNonce();
            String wrongToken = sign(signingKey, "apple-user", "wrong");
            assertThatThrownBy(() -> authentication.authenticate(wrongToken, "unused", wrongNonce))
                    .isInstanceOf(InvalidSocialCredentialException.class);
            assertThatThrownBy(() -> exchange.exchange("configuration"))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("configuration=");
            assertThatThrownBy(() -> exchange.exchange("outage"))
                    .isInstanceOf(SocialIdentityVerificationException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tokenExchangeTimeoutDoesNotRetryOrExposeCredentials() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        String pem = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        var secret = new AppleClientSecret("app", "team", "key", pem, CLOCK);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", request -> {
            calls.incrementAndGet();
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { request.sendResponseHeaders(503, -1); } finally { request.close(); }
        });
        server.start();
        try {
            var exchange = new AppleCodeExchange(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/token"), "app", secret, 1000, 30);
            assertThatThrownBy(() -> exchange.exchange("secret-code"))
                    .isInstanceOf(SocialIdentityVerificationException.class)
                    .hasMessageNotContaining("secret-code");
            assertThat(calls.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
}
