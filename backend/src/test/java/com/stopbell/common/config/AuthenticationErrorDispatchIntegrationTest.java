package com.stopbell.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.IOException;

import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.auth.exception.SocialIdentityVerificationException;
import com.stopbell.user.auth.identity.GoogleIdentityVerifier;
import com.stopbell.user.auth.service.JwtTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ActiveProfiles("test")
@Import(SecurityConfigurationIntegrationTest.TestRepositoryConfiguration.class)
class AuthenticationErrorDispatchIntegrationTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private GoogleIdentityVerifier googleIdentityVerifier;

    @Autowired
    private JwtTokenService jwtTokens;

    @ParameterizedTest
    @ValueSource(ints = {404, 500})
    @DisplayName("공개 Auth 요청의 ERROR dispatch는 원래 404와 500을 인증 401로 바꾸지 않는다")
    void preserve_public_error_dispatch_status(int status) throws Exception {
        when(googleIdentityVerifier.verify("dispatch-fixture"))
                .thenThrow(new ResponseStatusException(HttpStatusCode.valueOf(status), "private-error-marker"));

        HttpResponse<String> response = post("/auth/google", "{\"idToken\":\"dispatch-fixture\"}");

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).doesNotContain("private-error-marker", "stackTrace", "java.lang");
        assertError(response, status, status == 404 ? "NOT_FOUND" : "INTERNAL_SERVER_ERROR",
                status == 404 ? "Requested resource was not found." : "An internal server error occurred.");
    }

    @Test
    @DisplayName("실제 Servlet의 Google 인증 401과 Infrastructure 503은 안전한 JSON을 유지한다")
    void preserve_public_authentication_error_contracts() throws Exception {
        when(googleIdentityVerifier.verify("private-google-credential"))
                .thenThrow(new InvalidSocialCredentialException(new IllegalArgumentException("private-decoder-marker")));
        when(googleIdentityVerifier.verify("unavailable-google-credential"))
                .thenThrow(new SocialIdentityVerificationException(new IOException("private-key-fetch-marker")));

        assertError(post("/auth/google", "{\"idToken\":\"private-google-credential\"}"),
                401, "UNAUTHENTICATED", "Authentication is required or invalid.");
        assertError(post("/auth/google", "{\"idToken\":\"unavailable-google-credential\"}"),
                503, "SOCIAL_IDENTITY_UNAVAILABLE", "Social identity verification is temporarily unavailable.");
        assertError(post("/auth/refresh", "{\"refreshToken\":\"\"}"),
                401, "UNAUTHENTICATED", "Authentication is required or invalid.");
        HttpResponse<String> logout = post("/auth/logout", "{\"refreshToken\":null}");
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.body()).isEmpty();
    }

    @Test
    @DisplayName("공개 Auth의 미처리 Runtime 실패는 500이며 내부 예외를 본문에 노출하지 않는다")
    void preserve_unexpected_failure_status() throws Exception {
        when(googleIdentityVerifier.verify("runtime-fixture"))
                .thenThrow(new IllegalStateException("private-runtime-marker"));
        HttpResponse<String> response = post("/auth/google", "{\"idToken\":\"runtime-fixture\"}");
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("private-runtime-marker", "runtime-fixture", "IllegalStateException",
                "stackTrace", "java.lang");
        assertError(response, 500, "INTERNAL_SERVER_ERROR", "An internal server error occurred.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/google", "/auth/refresh", "/auth/logout", "/api/v1/alarms", "/error"})
    @DisplayName("ERROR 허용 뒤에도 원래 GET REQUEST의 인증 정책은 유지한다")
    void keep_original_request_protected(String path) throws Exception {
        assertError(get(path, null), 401, "UNAUTHENTICATED", "Authentication is required or invalid.");
    }

    @Test
    @DisplayName("인증된 미등록 경로는 실제 Servlet에서도 404를 보존하고 정상 JWT는 통과한다")
    void preserve_authenticated_not_found() throws Exception {
        HttpResponse<String> response = get("/api/v1/unknown-fixture", jwtTokens.createAccessToken(42L));
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("stackTrace", "java.lang");
        assertError(response, 404, "NOT_FOUND", "Requested resource was not found.");
        // This security test deliberately excludes the database; the endpoint stays public but is unready.
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(503);
    }

    private void assertError(HttpResponse<String> response, int status, String code, String message) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        var json = JsonMapper.builder().build().readTree(response.body());
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(json.get("code").asString()).isEqualTo(code);
        assertThat(json.get("message").asString()).isEqualTo(message);
        assertThat(response.body()).doesNotContain("private-", "credential", "stackTrace", "SQL", "java.lang");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
            if (token != null) request.header("Authorization", "Bearer " + token);
            return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
