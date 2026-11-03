package com.stopbell.user.auth.apple;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AppleTokenRevocationTest {
    private AppleClientSecret secret() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return new AppleClientSecret("app", "team", "key", Base64.getEncoder()
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded()), Clock.systemUTC());
    }

    @Test
    @DisplayName("실제 Refresh Token과 기존 Client Secret을 form으로 보내며 중복 철회 200도 성공이다")
    void revoke_and_repeat_with_same_token() throws Exception {
        var requests = new AtomicInteger();
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/revoke", request -> {
            assertThat(request.getRequestMethod()).isEqualTo("POST");
            assertThat(request.getRequestHeaders().getFirst("Content-Type"))
                    .isEqualTo("application/x-www-form-urlencoded");
            received.set(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.incrementAndGet();
            request.sendResponseHeaders(200, -1);
            request.close();
        });
        server.start();
        try {
            var client = client(server, secret(), 1000);
            assertThat(client.revoke("synthetic+refresh&token")).isEqualTo(AppleTokenRevocation.Outcome.REVOKED);
            assertThat(client.revoke("synthetic+refresh&token")).isEqualTo(AppleTokenRevocation.Outcome.REVOKED);
            var fields = new HashMap<String, String>();
            for (String part : received.get().split("&")) {
                var pair = part.split("=", 2);
                fields.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            assertThat(fields).containsEntry("client_id", "app").containsEntry("token", "synthetic+refresh&token")
                    .containsEntry("token_type_hint", "refresh_token").hasSize(4);
            assertThat(com.nimbusds.jwt.SignedJWT.parse(fields.get("client_secret")).getJWTClaimsSet().getSubject())
                    .isEqualTo("app");
            assertThat(requests.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_client", "unauthorized_client", "invalid_request"})
    @DisplayName("Apple의 인증 및 요청 설정 오류를 일시 장애와 구분한다")
    void classify_configuration_errors(String error) throws Exception {
        assertResponse(400, "{\"error\":\"" + error + "\"}", AppleTokenRevocation.Outcome.CONFIGURATION_ERROR);
    }

    @Test
    @DisplayName("400 invalid_grant는 이미 철회된 성공으로 간주하지 않는다")
    void reject_bad_token() throws Exception {
        assertResponse(400, "{\"error\":\"invalid_grant\",\"token\":\"synthetic-sensitive\"}",
                AppleTokenRevocation.Outcome.REJECTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503, 429, 204})
    @DisplayName("200 이외 상태는 철회 성공으로 확정하지 않는다")
    void unconfirmed_status(int status) throws Exception {
        assertResponse(status, status == 204 ? "" : "synthetic-sensitive", AppleTokenRevocation.Outcome.UNCERTAIN);
    }

    @Test
    @DisplayName("깨진 오류 JSON은 민감한 응답 없이 불확실로 처리한다")
    void malformed_response() throws Exception {
        assertResponse(400, "synthetic-sensitive", AppleTokenRevocation.Outcome.UNCERTAIN);
    }

    @Test
    @DisplayName("응답 크기 제한을 넘으면 성공으로 처리하지 않는다")
    void oversized_response() throws Exception {
        assertResponse(200, "x".repeat(65537), AppleTokenRevocation.Outcome.UNCERTAIN);
    }

    @Test
    @DisplayName("응답 시간 초과는 철회 여부 불확실로 반환한다")
    void response_timeout() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/revoke", request -> {
                request.getRequestBody().readAllBytes();
                try { release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                request.close();
            });
            server.start();
            try {
                assertThat(client(server, secret(), 50).revoke("synthetic-refresh"))
                        .isEqualTo(AppleTokenRevocation.Outcome.UNCERTAIN);
            } finally { release.countDown(); server.stop(0); }
        }
    }

    @Test
    @DisplayName("연결 실패는 민감한 예외 없이 불확실로 반환한다")
    void connection_failure() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.start(); server.stop(0);
        assertThat(new AppleTokenRevocation(URI.create("http://127.0.0.1:" + port + "/revoke"),
                "app", secret(), 50, 50).revoke("synthetic-refresh"))
                .isEqualTo(AppleTokenRevocation.Outcome.UNCERTAIN);
    }

    @Test
    @DisplayName("리다이렉트 위치로 Token과 Client Secret을 전달하지 않는다")
    void never_follow_redirects() throws Exception {
        var followed = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/revoke", request -> {
            request.getRequestBody().readAllBytes();
            request.getResponseHeaders().add("Location", "/target");
            request.sendResponseHeaders(307, -1); request.close();
        });
        server.createContext("/target", request -> { followed.incrementAndGet(); request.close(); });
        server.start();
        try {
            assertThat(client(server, secret(), 1000).revoke("synthetic-refresh"))
                    .isEqualTo(AppleTokenRevocation.Outcome.UNCERTAIN);
            assertThat(followed.get()).isZero();
        } finally { server.stop(0); }
    }

    @Test
    @DisplayName("Client Secret 생성 실패는 요청 전 설정 오류로 처리한다")
    void client_secret_failure() {
        var secret = mock(AppleClientSecret.class);
        when(secret.create()).thenThrow(new IllegalStateException("synthetic-sensitive"));
        assertThat(new AppleTokenRevocation("app", secret).revoke("synthetic-refresh"))
                .isEqualTo(AppleTokenRevocation.Outcome.CONFIGURATION_ERROR);
    }

    private AppleTokenRevocation client(HttpServer server, AppleClientSecret secret, int timeout) {
        return new AppleTokenRevocation(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/revoke"),
                "app", secret, timeout, timeout);
    }

    private void assertResponse(int status, String body, AppleTokenRevocation.Outcome expected) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/revoke", request -> {
            request.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (var out = request.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try { assertThat(client(server, secret(), 1000).revoke("synthetic-refresh")).isEqualTo(expected); }
        finally { server.stop(0); }
    }
}
