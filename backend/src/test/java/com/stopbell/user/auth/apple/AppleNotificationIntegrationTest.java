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
import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.auth.service.LoginService;
import com.stopbell.user.service.AccountDeletionService;
import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.entity.AuthProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.MessageDigest;
import java.util.UUID;
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
    @Autowired LoginService login;
    @Autowired AppleNotificationStore store;
    @Autowired AppleIdentityBoundary boundary;
    @Autowired AccountDeletionService deletion;
    @Autowired PlatformTransactionManager manager;
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
        long boundariesBefore = jdbc.queryForObject("select count(*) from apple_identity_boundaries", Long.class);
        RSAKey other = new RSAKeyGenerator(2048).keyID("other").generate();
        assertThat(send(sign(claims("consent-revoked", "id").build(), other, "key"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", "id").build(), key, "missing"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", "id").issuer("wrong").build(), key, "key"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", "id").audience("wrong").build(), key, "key"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", null).build(), key, "key"))).isNotEqualTo(200);
        assertThat(send(sign(claims("consent-revoked", "id").claim("events", Map.of("type", "consent-revoked")).build(), key, "key"))).isNotEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from apple_notification_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from apple_identity_boundaries", Long.class)).isEqualTo(boundariesBefore);
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
    private static byte[] jtiHash(String jti) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(jti.getBytes(StandardCharsets.UTF_8));
    }
    private JWTClaimsSet eventClaims(String type, String jti, String subject, Instant eventTime) {
        return new JWTClaimsSet.Builder().issuer("https://appleid.apple.com").audience("test.app")
                .issueTime(Date.from(Instant.now().minusSeconds(1))).jwtID(jti)
                .claim("events", Map.of("type", type, "sub", subject, "event_time", eventTime.getEpochSecond())).build();
    }
    @Test void signedRevocationBlocksOldAndSameSecondLoginAndProtectsReauthentication() throws Exception {
        String subject = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        Instant eventTime = Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var apple = new ExternalIdentity(AuthProvider.APPLE, subject);
        login.loginApple(apple, eventTime.minusSeconds(20), (u, t) -> {});
        long oldId = jdbc.queryForObject("select id from users where auth_provider='APPLE' and provider_user_id=?", Long.class, subject);
        assertThat(send(sign(eventClaims("consent-revoked", jti, subject, eventTime), key, "key"))).isEqualTo(200);
        // Simulate a future worker's local deletion in this isolated database.
        jdbc.update("delete from refresh_tokens where user_id=?", oldId);
        jdbc.update("delete from users where id=?", oldId);
        assertThatThrownBy(() -> login.loginApple(apple, eventTime.minusSeconds(1), (u, t) -> {}))
                .isInstanceOf(InvalidSocialCredentialException.class);
        assertThatThrownBy(() -> login.loginApple(apple, eventTime, (u, t) -> {}))
                .isInstanceOf(InvalidSocialCredentialException.class);
        assertThat(jdbc.queryForObject("select count(*) from users where auth_provider='APPLE' and provider_user_id=?", Integer.class, subject)).isZero();
        login.login(new ExternalIdentity(AuthProvider.GOOGLE, subject));
        login.loginApple(apple, eventTime.plusSeconds(2), (u, t) -> {});
        var restartedBoundary = new AppleIdentityBoundary(jdbc);
        var assessment = new TransactionTemplate(manager).execute(status -> restartedBoundary.lockPendingEvent(jtiHashUnchecked(jti)));
        assertThat(assessment.decision()).isEqualTo(AppleIdentityBoundary.TemporalDecision.NEWER_AUTHENTICATION);
        assertThat(jdbc.queryForObject("select count(*) from users where provider_user_id=?", Integer.class, subject)).isEqualTo(2);
        assertThat(send(sign(eventClaims("consent-revoked", jti, subject, eventTime), key, "key"))).isEqualTo(200);
        String delayed = UUID.randomUUID().toString();
        assertThat(send(sign(eventClaims("consent-revoked", delayed, subject, eventTime.minusSeconds(5)), key, "key"))).isEqualTo(200);
        assertThat(new TransactionTemplate(manager).execute(status -> boundary.lockPendingEvent(jtiHashUnchecked(delayed))).decision())
                .isEqualTo(AppleIdentityBoundary.TemporalDecision.NEWER_AUTHENTICATION);
        assertThat(jdbc.queryForObject("select count(*) from users where auth_provider='APPLE' and provider_user_id=?", Integer.class, subject)).isOne();
    }
    private static byte[] jtiHashUnchecked(String jti) {
        try { return jtiHash(jti); } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    @Test void eventAndOldLoginSerializeEvenWithoutUserAndSurviveRetry() throws Exception {
        String subject = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        Instant eventTime = Instant.now().minusSeconds(15).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var event = new AppleNotificationVerifier.Event(jti, "account-deleted", subject, eventTime);
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var eventTx = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                store.store(event);
                written.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                return true;
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            var loginTx = executor.submit(() -> {
                started.countDown();
                return login.loginApple(new ExternalIdentity(AuthProvider.APPLE, subject), eventTime.minusSeconds(1), (u, t) -> {});
            });
            try { assertThat(started.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            assertThat(eventTx.get(20, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> loginTx.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(InvalidSocialCredentialException.class);
        }
        assertThat(jdbc.queryForObject("select count(*) from users where auth_provider='APPLE' and provider_user_id=?", Integer.class, subject)).isZero();
        assertThatThrownBy(() -> login.loginApple(new ExternalIdentity(AuthProvider.APPLE, subject), eventTime, (u, t) -> {}))
                .isInstanceOf(InvalidSocialCredentialException.class);
    }
    @Test void signedEventPersistsThenDeletesOnlyItsAppleAccount() throws Exception {
        String subject = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        Instant eventTime = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var apple = new ExternalIdentity(AuthProvider.APPLE, subject);
        login.loginApple(apple, eventTime.minusSeconds(10), (u, t) -> {});
        login.login(new ExternalIdentity(AuthProvider.GOOGLE, subject));
        assertThat(send(sign(eventClaims("account-deleted", jti, subject, eventTime), key, "key"))).isEqualTo(200);
        assertThat(deletion.prepareAppleEvent(jtiHash(jti))).isEqualTo(AccountDeletionService.EventResult.READY);
        assertThat(deletion.deleteForAppleEvent(jtiHash(jti))).isEqualTo(AccountDeletionService.EventResult.DELETED);
        assertThat(jdbc.queryForObject("select count(*) from users where auth_provider='APPLE' and provider_user_id=?", Integer.class, subject)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where auth_provider='GOOGLE' and provider_user_id=?", Integer.class, subject)).isOne();
        assertThat(send(sign(eventClaims("account-deleted", jti, subject, eventTime), key, "key"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("select state from apple_notification_events where jti_hash=?", String.class, jtiHash(jti))).isEqualTo("DELETED");
    }
    @Test void pendingAssessmentUsesCurrentReadAfterAnOlderRepeatableReadSnapshot() throws Exception {
        String subject = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        Instant eventTime = Instant.now().minusSeconds(20).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var identity = new ExternalIdentity(AuthProvider.APPLE, subject);
        store.store(new AppleNotificationVerifier.Event(jti, "consent-revoked", subject, eventTime));
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction.execute(status -> {
                jdbc.queryForObject("select count(*) from apple_notification_events", Integer.class);
                var reauth = executor.submit(() -> login.loginApple(identity, eventTime.plusSeconds(5), (u, t) -> {}));
                try { assertThat(reauth.get(10, TimeUnit.SECONDS)).isNotNull(); }
                catch (Exception ex) { throw new IllegalStateException(ex); }
                assertThat(boundary.lockPendingEvent(jtiHashUnchecked(jti)).decision())
                        .isEqualTo(AppleIdentityBoundary.TemporalDecision.NEWER_AUTHENTICATION);
                return null;
            });
        }
    }
    @Test void pendingAssessmentAndSimulatedDeletionFenceAWaitingOldLogin() throws Exception {
        String subject = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        Instant eventTime = Instant.now().minusSeconds(20).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var identity = new ExternalIdentity(AuthProvider.APPLE, subject);
        login.loginApple(identity, eventTime.minusSeconds(10), (u, t) -> {});
        long id = jdbc.queryForObject("select id from users where auth_provider='APPLE' and provider_user_id=?", Long.class, subject);
        store.store(new AppleNotificationVerifier.Event(jti, "consent-revoked", subject, eventTime));
        var deleted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var worker = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                assertThat(boundary.lockPendingEvent(jtiHashUnchecked(jti)).decision())
                        .isEqualTo(AppleIdentityBoundary.TemporalDecision.EVENT_AFTER_AUTHENTICATION);
                jdbc.update("delete from refresh_tokens where user_id=?", id);
                jdbc.update("delete from users where id=?", id);
                deleted.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                return true;
            }));
            assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();
            var delayed = executor.submit(() -> {
                started.countDown();
                return login.loginApple(identity, eventTime.minusSeconds(1), (u, t) -> {});
            });
            try { assertThat(started.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            assertThat(worker.get(20, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> delayed.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(InvalidSocialCredentialException.class);
        }
        assertThat(jdbc.queryForObject("select count(*) from users where auth_provider='APPLE' and provider_user_id=?", Integer.class, subject)).isZero();
        login.loginApple(identity, eventTime.plusSeconds(2), (u, t) -> {});
        assertThat(jdbc.queryForObject("select count(*) from users where auth_provider='APPLE' and provider_user_id=?", Integer.class, subject)).isOne();
    }
    @Test void differentAppleSubjectsDoNotBlockOneAnother() throws Exception {
        String subject = UUID.randomUUID().toString();
        var event = new AppleNotificationVerifier.Event(UUID.randomUUID().toString(), "account-deleted", subject,
                Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                store.store(event);
                holding.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                return true;
            }));
            try {
                assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
                var independent = executor.submit(() -> login.loginApple(
                        new ExternalIdentity(AuthProvider.APPLE, UUID.randomUUID().toString()), Instant.now(), (u, t) -> {}));
                assertThat(independent.get(5, TimeUnit.SECONDS)).isNotNull();
            } finally { release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void rollbackRemovesEventAndBoundaryTogether() {
        String subject = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        var event = new AppleNotificationVerifier.Event(jti, "consent-revoked", subject, Instant.now().minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            store.store(event);
            throw new IllegalStateException("rollback fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from apple_notification_events where jti_hash=?", Integer.class, jtiHashUnchecked(jti))).isZero();
        login.loginApple(new ExternalIdentity(AuthProvider.APPLE, subject), event.eventTime().minusSeconds(10), (u, t) -> {});
    }
    @Test void nullOrEqualAuthenticationRequiresReviewAndEarlierAuthenticationCanBeAssessed() {
        String subject = UUID.randomUUID().toString();
        Instant eventTime = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String jti = UUID.randomUUID().toString();
        jdbc.update("insert into users (auth_provider,provider_user_id,created_at,updated_at) values ('APPLE',?,now(),now())", subject);
        store.store(new AppleNotificationVerifier.Event(jti, "consent-revoked", subject, eventTime));
        var tx = new TransactionTemplate(manager);
        assertThat(tx.execute(status -> boundary.lockPendingEvent(jtiHashUnchecked(jti))).decision())
                .isEqualTo(AppleIdentityBoundary.TemporalDecision.REVIEW_REQUIRED);
        String subject2 = UUID.randomUUID().toString();
        String jti2 = UUID.randomUUID().toString();
        login.loginApple(new ExternalIdentity(AuthProvider.APPLE, subject2), eventTime.minusSeconds(10), (u, t) -> {});
        store.store(new AppleNotificationVerifier.Event(jti2, "consent-revoked", subject2, eventTime));
        assertThat(tx.execute(status -> boundary.lockPendingEvent(jtiHashUnchecked(jti2))).decision())
                .isEqualTo(AppleIdentityBoundary.TemporalDecision.EVENT_AFTER_AUTHENTICATION);
        String subject3 = UUID.randomUUID().toString();
        String jti3 = UUID.randomUUID().toString();
        login.loginApple(new ExternalIdentity(AuthProvider.APPLE, subject3), eventTime, (u, t) -> {});
        store.store(new AppleNotificationVerifier.Event(jti3, "account-deleted", subject3, eventTime.plusMillis(500)));
        assertThat(tx.execute(status -> boundary.lockPendingEvent(jtiHashUnchecked(jti3))).decision())
                .isEqualTo(AppleIdentityBoundary.TemporalDecision.REVIEW_REQUIRED);
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
