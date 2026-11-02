package com.stopbell.user.auth.apple;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Base64;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.stopbell.common.error.GlobalExceptionHandler;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.auth.exception.SocialIdentityVerificationException;
import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.auth.identity.GoogleIdentityVerifier;
import com.stopbell.user.auth.service.JwtTokenService;
import com.stopbell.user.auth.service.LoginService;
import com.stopbell.user.auth.service.RefreshTokenService;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "apple.authentication.enabled=true",
        "apple.authentication.credential-key-id=test",
        "apple.authentication.credential-keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AppleLoginIntegrationTest {
    @Container @ServiceConnection static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
            .withCommand("--log-bin-trust-function-creators=1");
    @Autowired MockMvc mvc;
    @Autowired LoginService login;
    @Autowired AppleCredentialStore credentials;
    @Autowired AppleCredentialCipher cipher;
    @MockitoSpyBean UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AppleAuthentication authentication;
    @MockitoBean GoogleIdentityVerifier google;
    @MockitoSpyBean RefreshTokenService refresh;

    private static String signed(RSAKey key, String subject, String nonce, Instant now) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://appleid.apple.com").audience("app")
                .subject(subject).issueTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(300)));
        if (nonce != null) claims.claim("nonce", nonce);
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key").build(), claims.build());
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    @Test
    void realVerifierAndMockAppleServerWorkThroughHttpAndDatabase() throws Exception {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        RSAKey rsa = new RSAKeyGenerator(2048).keyID("key").generate();
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        String pem = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        String subject = UUID.randomUUID().toString();
        String exchangeToken = signed(rsa, subject, null, now);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", request -> {
            byte[] body = new JWKSet(rsa.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, body.length);
            try (var output = request.getResponseBody()) { output.write(body); }
        });
        server.createContext("/token", request -> {
            String form = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean invalid = form.contains("code=invalid");
            String body = invalid ? "{\"error\":\"invalid_grant\"}"
                    : "{\"id_token\":\"" + exchangeToken + "\",\"refresh_token\":\"apple-secret\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(invalid ? 400 : 200, bytes.length);
            try (var output = request.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            var real = new AppleAuthentication(new AppleNonceStore(clock),
                    new AppleIdentityTokenVerifier(new AppleJwksDecoder(URI.create(origin + "/keys"), 1000, 1000), "app", clock),
                    new AppleCodeExchange(URI.create(origin + "/token"), "app",
                            new AppleClientSecret("app", "team", "key", pem, clock), 1000, 1000));
            var http = MockMvcBuilders.standaloneSetup(new AppleLoginController(real, credentials, login))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            var json = JsonMapper.builder().build();
            String challenge = http.perform(post("/auth/apple/challenge")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String nonce = json.readTree(challenge).get("nonce").asString();
            String token = signed(rsa, subject, nonce, now);
            String body = json.writeValueAsString(java.util.Map.of("identityToken", token, "authorizationCode", "ok", "nonce", nonce));
            http.perform(post("/auth/apple").contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
            http.perform(post("/auth/apple").contentType("application/json").content(body))
                    .andExpect(status().isUnauthorized());
            long id = users.findByAuthProviderAndProviderUserId(AuthProvider.APPLE, subject).orElseThrow().getId();
            String stored = jdbc.queryForObject("select encrypted_refresh_token from apple_credentials where user_id=?", String.class, id);
            assertThat(cipher.decrypt(id, stored)).isEqualTo("apple-secret");
            String invalidNonce = real.issueNonce();
            String invalidBody = json.writeValueAsString(java.util.Map.of("identityToken", signed(rsa, subject, invalidNonce, now),
                    "authorizationCode", "invalid", "nonce", invalidNonce));
            http.perform(post("/auth/apple").contentType("application/json").content(invalidBody))
                    .andExpect(status().isUnauthorized());
        } finally { server.stop(0); }
    }

    @Test
    void httpIssuesChallengeAndPersistsEncryptedCredentialForAppleUser() throws Exception {
        String subject = UUID.randomUUID().toString();
        when(authentication.issueNonce()).thenReturn("nonce");
        when(authentication.authenticate("id", "code", "nonce"))
                .thenReturn(new AppleAuthentication.Result(subject, "apple-secret"));
        mvc.perform(post("/auth/apple/challenge")).andExpect(status().isOk())
                .andExpect(jsonPath("$.nonce").value("nonce"))
                .andExpect(header().string("Cache-Control", "no-store"));
        var response = mvc.perform(post("/auth/apple").contentType("application/json")
                        .content("{\"identityToken\":\"id\",\"authorizationCode\":\"code\",\"nonce\":\"nonce\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        var user = users.findByAuthProviderAndProviderUserId(AuthProvider.APPLE, subject).orElseThrow();
        String encrypted = jdbc.queryForObject("select encrypted_refresh_token from apple_credentials where user_id=?",
                String.class, user.getId());
        assertThat(encrypted).doesNotContain("apple-secret");
        assertThat(cipher.decrypt(user.getId(), encrypted)).isEqualTo("apple-secret");
        assertThat(response).doesNotContain("apple-secret");
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id=?", Integer.class, user.getId()))
                .isEqualTo(1);
        login.loginApple(new ExternalIdentity(AuthProvider.APPLE, subject), u -> credentials.store(u, "new-secret"));
        String updated = jdbc.queryForObject("select encrypted_refresh_token from apple_credentials where user_id=?",
                String.class, user.getId());
        assertThat(cipher.decrypt(user.getId(), updated)).isEqualTo("new-secret");
        assertThat(updated).isNotEqualTo(encrypted);
        login.login(new ExternalIdentity(AuthProvider.GOOGLE, subject));
        assertThat(users.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, subject).orElseThrow().getId())
                .isNotEqualTo(user.getId());
    }

    @Test
    void invalidCredentialAndUnavailableAppleDoNotIssueSessions() throws Exception {
        when(authentication.authenticate("bad", "code", "nonce"))
                .thenThrow(new InvalidSocialCredentialException());
        when(authentication.authenticate("outage", "code", "nonce"))
                .thenThrow(new SocialIdentityVerificationException(new IllegalStateException("fixture")));
        long before = jdbc.queryForObject("select count(*) from refresh_tokens", Long.class);
        for (var entry : java.util.Map.of("bad", 401, "outage", 503).entrySet()) {
            mvc.perform(post("/auth/apple").contentType("application/json")
                            .content("{\"identityToken\":\"" + entry.getKey()
                                    + "\",\"authorizationCode\":\"code\",\"nonce\":\"nonce\"}"))
                    .andExpect(status().is(entry.getValue()));
        }
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Long.class)).isEqualTo(before);
    }

    @Test
    void rollsBackUserAndCredentialOnFailures() {
        String first = UUID.randomUUID().toString();
        var identity = new ExternalIdentity(AuthProvider.APPLE, first);
        assertThatThrownBy(() -> login.loginApple(identity, user -> {
            credentials.store(user, "secret");
            throw new IllegalStateException("fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(users.findByAuthProviderAndProviderUserId(AuthProvider.APPLE, first)).isEmpty();
        String invalidCredential = UUID.randomUUID().toString();
        assertThatThrownBy(() -> login.loginApple(new ExternalIdentity(AuthProvider.APPLE, invalidCredential),
                user -> credentials.store(user, "x".repeat(8193))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(users.findByAuthProviderAndProviderUserId(AuthProvider.APPLE, invalidCredential)).isEmpty();
        String dbFailure = UUID.randomUUID().toString();
        assertThatThrownBy(() -> login.loginApple(new ExternalIdentity(AuthProvider.APPLE, dbFailure), user -> {
            credentials.store(user, "secret");
            jdbc.update("insert into apple_credentials (user_id, encrypted_refresh_token) values (?, ?)",
                    user.getId(), "duplicate");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(users.findByAuthProviderAndProviderUserId(AuthProvider.APPLE, dbFailure)).isEmpty();
        String second = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("fixture")).when(refresh).issue(any());
        assertThatThrownBy(() -> login.loginApple(new ExternalIdentity(AuthProvider.APPLE, second),
                user -> credentials.store(user, "secret"))).isInstanceOf(IllegalStateException.class);
        assertThat(users.findByAuthProviderAndProviderUserId(AuthProvider.APPLE, second)).isEmpty();
    }

    @Test
    void credentialInsertDatabaseFailureRollsBackUserAndTokens() {
        String subject = UUID.randomUUID().toString();
        long usersBefore = jdbc.queryForObject("select count(*) from users", Long.class);
        long tokensBefore = jdbc.queryForObject("select count(*) from refresh_tokens", Long.class);
        jdbc.execute("CREATE TRIGGER apple_fixture_reject BEFORE INSERT ON apple_credentials "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fixture failure'");
        try {
            assertThatThrownBy(() -> login.loginApple(new ExternalIdentity(AuthProvider.APPLE, subject),
                    user -> credentials.store(user, "fixture-secret"))).isInstanceOf(RuntimeException.class);
            assertThat(users.findByAuthProviderAndProviderUserId(AuthProvider.APPLE, subject)).isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(usersBefore);
            assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Long.class)).isEqualTo(tokensBefore);
        } finally {
            jdbc.execute("DROP TRIGGER apple_fixture_reject");
        }
    }

    @Test
    void refreshIssuanceFailureRestoresExistingCredentialAndOtherUserData() {
        var first = new ExternalIdentity(AuthProvider.APPLE, UUID.randomUUID().toString());
        var other = new ExternalIdentity(AuthProvider.APPLE, UUID.randomUUID().toString());
        login.loginApple(first, user -> credentials.store(user, "first-before"));
        login.loginApple(other, user -> credentials.store(user, "other-before"));
        long firstId = users.findByAuthProviderAndProviderUserId(AuthProvider.APPLE, first.providerUserId()).orElseThrow().getId();
        long otherId = users.findByAuthProviderAndProviderUserId(AuthProvider.APPLE, other.providerUserId()).orElseThrow().getId();
        String firstBefore = jdbc.queryForObject("select encrypted_refresh_token from apple_credentials where user_id=?", String.class, firstId);
        String otherBefore = jdbc.queryForObject("select encrypted_refresh_token from apple_credentials where user_id=?", String.class, otherId);
        doThrow(new IllegalStateException("fixture")).when(refresh).issue(any());
        assertThatThrownBy(() -> login.loginApple(first, user -> credentials.store(user, "replacement")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select encrypted_refresh_token from apple_credentials where user_id=?", String.class, firstId))
                .isEqualTo(firstBefore);
        assertThat(jdbc.queryForObject("select encrypted_refresh_token from apple_credentials where user_id=?", String.class, otherId))
                .isEqualTo(otherBefore);
    }

    @Test
    void simultaneousFirstLoginSharesUserAndStoresTwoSessions() throws Exception {
        String subject = UUID.randomUUID().toString();
        var identity = new ExternalIdentity(AuthProvider.APPLE, subject);
        CountDownLatch bothRead = new CountDownLatch(2);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(call -> {
            Object result = mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(call);
            if (reads.incrementAndGet() <= 2) {
                assertThat((java.util.Optional<?>) result).isEmpty();
                bothRead.countDown();
                assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(users).findByAuthProviderAndProviderUserId(AuthProvider.APPLE, subject);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> login.loginApple(identity, user -> credentials.store(user, "secret-one")));
            var two = executor.submit(() -> login.loginApple(identity, user -> credentials.store(user, "secret-two")));
            var first = one.get(30, TimeUnit.SECONDS);
            var second = two.get(30, TimeUnit.SECONDS);
            long id = jwt.extractUserId(first.accessToken());
            assertThat(jwt.extractUserId(second.accessToken())).isEqualTo(id);
            assertThat(jdbc.queryForObject("select count(*) from apple_credentials where user_id=?", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id=?", Integer.class, id)).isEqualTo(2);
            assertThat(reads.get()).isGreaterThanOrEqualTo(3);
        }
    }
}
