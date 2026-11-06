package com.stopbell.user.auth.apple;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.stopbell.support.SharedMySqlExtension;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = {"apple.notifications.enabled=true", "apple.notifications.client-id=test.app", "account.deletion.recovery-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppleNotificationIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { SharedMySqlExtension.database(registry, "apple_notification"); }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AppleNotificationVerifier configuredVerifier;
    static RSAKey key;
    static HttpServer server;
    AppleNotificationVerifier verifier;

    @BeforeAll static void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", exchange -> {
            byte[] bytes = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }
    @AfterAll static void stop() { server.stop(0); }
    @BeforeEach void setup() {
        jdbc.update("delete from apple_notification_events");
        verifier = new AppleNotificationVerifier(new AppleJwksDecoder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/keys"), 1000, 1000), "test.app", Clock.systemUTC());
        when(configuredVerifier.verify(any())).thenAnswer(invocation -> verifier.verify(invocation.getArgument(0)));
    }
    JWTClaimsSet.Builder claims(String type, String jti) {
        return new JWTClaimsSet.Builder().issuer("https://appleid.apple.com").audience("test.app")
                .issueTime(Date.from(Instant.now().minusSeconds(5))).jwtID(jti)
                .claim("events", Map.of("type", type, "sub", "apple-user", "event_time", Instant.now().minusSeconds(5).getEpochSecond()));
    }
    String sign(JWTClaimsSet claims, RSAKey signingKey, String kid) throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
        token.sign(new RSASSASigner(signingKey));
        return token.serialize();
    }
    int send(String token) throws Exception {
        return mvc.perform(post("/auth/apple/notifications").contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":\"" + token + "\"}")).andReturn().getResponse().getStatus();
    }
    @Test void fourTypesAndDuplicateAreDurable() throws Exception {
        for (String type : java.util.List.of("consent-revoked", "account-deleted", "email-enabled", "email-disabled")) {
            String token = sign(claims(type, type).build(), key, "key");
            assertThat(send(token)).isEqualTo(200);
            assertThat(send(token)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("select count(*) from apple_notification_events", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from apple_notification_events where state='PENDING' and apple_subject='apple-user'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from apple_notification_events where state='IGNORED' and apple_subject is null", Integer.class)).isEqualTo(2);
    }
    @Test void rejectsBadSignatureKidIssuerAudienceAndMissingClaims() throws Exception {
        RSAKey other = new RSAKeyGenerator(2048).keyID("other").generate();
        assertThat(send(sign(claims("consent-revoked", "id").build(), other, "key"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", "id").build(), key, "missing"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", "id").issuer("wrong").build(), key, "key"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", "id").audience("wrong").build(), key, "key"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", null).build(), key, "key"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", "id").claim("events", Map.of("type", "consent-revoked")).build(), key, "key"))).isNotEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from apple_notification_events", Integer.class)).isZero();
    }
    @Test void optionalTimesAndMissingFieldsAreValidated() throws Exception {
        var now = Instant.now();
        assertThat(send(sign(claims("consent-revoked", "exp").expirationTime(Date.from(now.minusSeconds(1))).build(), key, "key"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", "nbf").notBeforeTime(Date.from(now.plusSeconds(60))).build(), key, "key"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", "iat").issueTime(Date.from(now.plusSeconds(60))).build(), key, "key"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", "type").claim("events", Map.of("sub", "u", "event_time", 1)).build(), key, "key"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", "time").claim("events", Map.of("sub", "u", "type", "consent-revoked")).build(), key, "key"))).isNotEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from apple_notification_events", Integer.class)).isZero();
    }
    @Test void concurrentDuplicatesAreStoredOnce() throws Exception {
        String token = sign(claims("account-deleted", "simultaneous").build(), key, "key");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); return send(token); });
            var two = pool.submit(() -> { start.await(); return send(token); });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(two.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("select count(*) from apple_notification_events", Integer.class)).isEqualTo(1);
    }
}
