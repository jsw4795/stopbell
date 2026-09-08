package com.stopbell.user.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.RefreshToken;
import com.stopbell.user.entity.User;
import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.auth.exception.SocialIdentityVerificationException;
import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.auth.identity.GoogleIdentityVerifier;
import com.stopbell.user.auth.service.JwtTokenService;
import com.stopbell.user.auth.service.RefreshTokenService;
import com.stopbell.user.repository.RefreshTokenRepository;
import com.stopbell.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import({
        AuthControllerIntegrationTest.TestGoogleIdentityVerifierConfiguration.class,
        AuthControllerIntegrationTest.TestProtectedEndpointConfiguration.class
})
class AuthControllerIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GoogleIdentityVerifier googleIdentityVerifier;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    @DisplayName("Access Token 없이 Google Login Endpoint에 접근해 Token Pair와 해시 저장 Refresh Token을 받는다")
    void allow_unauthenticated_google_login() throws Exception {
        when(googleIdentityVerifier.verify("valid-google-id-token"))
                .thenReturn(new ExternalIdentity(AuthProvider.GOOGLE, "controller-google-user"));

        MvcResult result = mockMvc.perform(post("/auth/google")
                        .contentType("application/json")
                        .content("{\"idToken\":\"valid-google-id-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String refreshToken = responseValue(responseBody, "refreshToken");
        User user = userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, "controller-google-user")
                .orElseThrow();

        assertThat(jwtTokenService.extractUserId(responseValue(responseBody, "accessToken"))).isEqualTo(user.getId());
        RefreshToken storedRefreshToken = refreshTokenRepository.findByTokenHash(sha256(refreshToken)).orElseThrow();
        assertThat(storedRefreshToken.getUser().getId()).isEqualTo(user.getId());
        assertThat(storedRefreshToken.getTokenHash()).isNotEqualTo(refreshToken);
    }

    @Test
    @DisplayName("Google Login으로 발급된 Access Token은 실제 Security Filter Chain을 통과해 내부 User를 식별한다")
    void authenticate_protected_endpoint_with_access_token_issued_by_google_login() throws Exception {
        TokenPair tokenPair = loginWithGoogle("protected-endpoint-google-id-token", "protected-endpoint-user");
        User user = userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, "protected-endpoint-user")
                .orElseThrow();

        assertThat(jwtTokenService.extractUserId(tokenPair.accessToken())).isEqualTo(user.getId());

        mockMvc.perform(get("/test/authentication")
                        .header("Authorization", "Bearer " + tokenPair.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId().toString()));
    }

    @Test
    @DisplayName("동일한 Google Identity 재로그인은 기존 User와 기존 Refresh Session을 유지한다")
    void reuse_user_and_keep_existing_refresh_session_when_google_user_logs_in_again() throws Exception {
        long userCountBeforeLogin = userRepository.count();
        TokenPair firstTokenPair = loginWithGoogle("relogin-google-id-token", "relogin-google-user");
        TokenPair secondTokenPair = loginWithGoogle("relogin-google-id-token", "relogin-google-user");
        User user = userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, "relogin-google-user")
                .orElseThrow();

        assertThat(userRepository.count()).isEqualTo(userCountBeforeLogin + 1);
        assertThat(jwtTokenService.extractUserId(firstTokenPair.accessToken())).isEqualTo(user.getId());
        assertThat(jwtTokenService.extractUserId(secondTokenPair.accessToken())).isEqualTo(user.getId());
        assertThat(refreshTokenRepository.findByTokenHash(sha256(firstTokenPair.refreshToken()))).isPresent();
        assertThat(refreshTokenRepository.findByTokenHash(sha256(secondTokenPair.refreshToken()))).isPresent();
    }

    @Test
    @DisplayName("Login Session을 Rotation하면 기존 Token 재사용을 막고 다른 Session은 유지한다")
    void rotate_login_session_prevents_reuse_and_keeps_other_session() throws Exception {
        TokenPair firstLogin = loginWithGoogle("rotation-flow-google-id-token", "rotation-flow-user");
        TokenPair secondLogin = loginWithGoogle("rotation-flow-google-id-token", "rotation-flow-user");

        TokenPair rotatedTokenPair = refresh(firstLogin.refreshToken());

        assertThat(rotatedTokenPair.refreshToken()).isNotEqualTo(firstLogin.refreshToken());
        assertThat(refreshTokenRepository.findByTokenHash(sha256(firstLogin.refreshToken()))).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(sha256(rotatedTokenPair.refreshToken()))).isPresent();
        assertThat(refreshTokenRepository.findByTokenHash(sha256(secondLogin.refreshToken()))).isPresent();

        mockMvc.perform(get("/test/authentication")
                        .header("Authorization", "Bearer " + rotatedTokenPair.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + firstLogin.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());

        TokenPair refreshedAgainTokenPair = refresh(rotatedTokenPair.refreshToken());
        assertThat(refreshedAgainTokenPair.refreshToken()).isNotEqualTo(rotatedTokenPair.refreshToken());
        assertThat(refreshTokenRepository.findByTokenHash(sha256(rotatedTokenPair.refreshToken()))).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(sha256(refreshedAgainTokenPair.refreshToken()))).isPresent();
        assertThat(refreshTokenRepository.findByTokenHash(sha256(secondLogin.refreshToken()))).isPresent();
    }

    @Test
    @DisplayName("Logout은 현재 Session만 삭제하고 이미 발급된 Access Token과 다른 Session은 유지한다")
    void logout_current_session_keeps_existing_access_token_and_other_session() throws Exception {
        TokenPair firstLogin = loginWithGoogle("logout-flow-google-id-token", "logout-flow-user");
        TokenPair secondLogin = loginWithGoogle("logout-flow-google-id-token", "logout-flow-user");

        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + firstLogin.refreshToken() + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(refreshTokenRepository.findByTokenHash(sha256(firstLogin.refreshToken()))).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(sha256(secondLogin.refreshToken()))).isPresent();

        mockMvc.perform(get("/test/authentication")
                        .header("Authorization", "Bearer " + firstLogin.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + firstLogin.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());

        refresh(secondLogin.refreshToken());
    }

    @Test
    @DisplayName("잘못된 Google Credential은 401 Unauthorized를 반환한다")
    void reject_invalid_google_credential() throws Exception {
        doThrow(new InvalidSocialCredentialException())
                .when(googleIdentityVerifier)
                .verify("invalid-google-id-token");

        mockMvc.perform(post("/auth/google")
                        .contentType("application/json")
                        .content("{\"idToken\":\"invalid-google-id-token\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Google Identity 검증 I/O 실패는 503 Service Unavailable을 반환한다")
    void return_service_unavailable_when_google_identity_verification_fails() throws Exception {
        doThrow(new SocialIdentityVerificationException(new IOException("Google public keys unavailable")))
                .when(googleIdentityVerifier)
                .verify("unavailable-google-id-token");

        mockMvc.perform(post("/auth/google")
                        .contentType("application/json")
                        .content("{\"idToken\":\"unavailable-google-id-token\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("Google public keys unavailable"))));
    }

    @Test
    @DisplayName("POST 이외의 Google Login 경로는 인증 없이 접근할 수 없다")
    void keep_non_post_google_login_path_protected() throws Exception {
        mockMvc.perform(get("/auth/google"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("유효한 Refresh Token은 Access Token과 Rotation된 Refresh Token을 반환한다")
    void rotate_valid_refresh_token() throws Exception {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "refresh-api-user"));
        String firstRefreshToken = refreshTokenService.issue(user);

        MvcResult result = mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + firstRefreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String secondRefreshToken = responseValue(responseBody, "refreshToken");

        assertThat(secondRefreshToken).isNotEqualTo(firstRefreshToken);
        assertThat(jwtTokenService.extractUserId(responseValue(responseBody, "accessToken"))).isEqualTo(user.getId());
        assertThat(refreshTokenRepository.findByTokenHash(sha256(firstRefreshToken))).isEmpty();
        RefreshToken rotatedRefreshToken = refreshTokenRepository.findByTokenHash(sha256(secondRefreshToken)).orElseThrow();
        assertThat(rotatedRefreshToken.getUser().getId()).isEqualTo(user.getId());
        assertThat(rotatedRefreshToken.getExpiresAt()).isAfter(now().plusDays(29));
        assertThat(rotatedRefreshToken.getExpiresAt()).isBefore(now().plusDays(31));
    }

    @Test
    @DisplayName("Rotation된 기존 Refresh Token을 다시 사용하면 401 Unauthorized를 반환한다")
    void reject_reused_refresh_token() throws Exception {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "reused-refresh-token-user"));
        String refreshToken = refreshTokenService.issue(user);

        mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("만료된 Refresh Token은 401 Unauthorized를 반환하고 저장된 Token을 삭제한다")
    void reject_expired_refresh_token() throws Exception {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "expired-refresh-token-user"));
        String expiredToken = "expired-refresh-token";
        String expiredTokenHash = sha256(expiredToken);
        refreshTokenRepository.saveAndFlush(new RefreshToken(user, expiredTokenHash, now().minusSeconds(1)));

        mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + expiredToken + "\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(refreshTokenRepository.findByTokenHash(expiredTokenHash)).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 Refresh Token은 401 Unauthorized를 반환한다")
    void reject_unknown_refresh_token() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"unknown-refresh-token\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비어 있거나 null인 Refresh Token은 401 Unauthorized를 반환한다")
    void reject_blank_or_null_refresh_token() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":null}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("한 Refresh Token을 Rotation해도 같은 User의 다른 Session은 유지된다")
    void retain_other_refresh_token_sessions_when_rotating() throws Exception {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "multiple-refresh-session-user"));
        String firstRefreshToken = refreshTokenService.issue(user);
        String secondRefreshToken = refreshTokenService.issue(user);

        MvcResult result = mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + firstRefreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String rotatedRefreshToken = responseValue(result.getResponse().getContentAsString(), "refreshToken");
        assertThat(refreshTokenRepository.findByTokenHash(sha256(firstRefreshToken))).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(sha256(rotatedRefreshToken))).isPresent();
        assertThat(refreshTokenRepository.findByTokenHash(sha256(secondRefreshToken))).isPresent();
    }

    @Test
    @DisplayName("GET Refresh 경로는 인증 없이 접근할 수 없다")
    void keep_non_post_refresh_path_protected() throws Exception {
        mockMvc.perform(get("/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Access Token 없이 Logout하면 현재 Refresh Token Session을 삭제하고 204를 반환한다")
    void logout_deletes_current_refresh_token_session() throws Exception {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "logout-api-user"));
        String refreshToken = refreshTokenService.issue(user);
        String tokenHash = sha256(refreshToken);

        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(refreshTokenRepository.findByTokenHash(tokenHash)).isEmpty();
    }

    @Test
    @DisplayName("Logout된 Refresh Token으로 재발급을 요청하면 401 Unauthorized를 반환한다")
    void reject_refresh_after_logout() throws Exception {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "logout-then-refresh-user"));
        String refreshToken = refreshTokenService.issue(user);

        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("동일한 Refresh Token을 다시 Logout해도 204를 반환한다")
    void logout_is_idempotent() throws Exception {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "idempotent-logout-user"));
        String refreshToken = refreshTokenService.issue(user);

        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("존재하지 않는 Refresh Token Logout은 DB 변경 없이 204를 반환한다")
    void logout_unknown_refresh_token() throws Exception {
        long refreshTokenCount = refreshTokenRepository.count();

        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"unknown-refresh-token-for-logout\"}"))
                .andExpect(status().isNoContent());

        assertThat(refreshTokenRepository.count()).isEqualTo(refreshTokenCount);
    }

    @Test
    @DisplayName("만료된 Refresh Token Logout은 Session을 삭제하고 204를 반환한다")
    void logout_deletes_expired_refresh_token() throws Exception {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "expired-logout-user"));
        String expiredToken = "expired-refresh-token-for-logout";
        String expiredTokenHash = sha256(expiredToken);
        refreshTokenRepository.saveAndFlush(new RefreshToken(user, expiredTokenHash, now().minusSeconds(1)));

        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + expiredToken + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(refreshTokenRepository.findByTokenHash(expiredTokenHash)).isEmpty();
    }

    @Test
    @DisplayName("null 또는 blank Refresh Token Logout은 204를 반환한다")
    void logout_accepts_null_or_blank_refresh_token() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":null}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("한 Refresh Token을 Logout해도 같은 User의 다른 Session은 유지된다")
    void retain_other_refresh_token_sessions_when_logging_out() throws Exception {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "multiple-logout-session-user"));
        String firstRefreshToken = refreshTokenService.issue(user);
        String secondRefreshToken = refreshTokenService.issue(user);

        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + firstRefreshToken + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(refreshTokenRepository.findByTokenHash(sha256(firstRefreshToken))).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(sha256(secondRefreshToken))).isPresent();
    }

    @Test
    @DisplayName("GET Logout 경로는 인증 없이 접근할 수 없다")
    void keep_non_post_logout_path_protected() throws Exception {
        mockMvc.perform(get("/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String responseValue(String responseBody, String fieldName) {
        Matcher matcher = Pattern.compile("\\\"" + fieldName + "\\\":\\\"([^\\\"]+)\\\"")
                .matcher(responseBody);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private TokenPair loginWithGoogle(String googleIdToken, String providerUserId) throws Exception {
        when(googleIdentityVerifier.verify(googleIdToken))
                .thenReturn(new ExternalIdentity(AuthProvider.GOOGLE, providerUserId));

        MvcResult result = mockMvc.perform(post("/auth/google")
                        .contentType("application/json")
                        .content("{\"idToken\":\"" + googleIdToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        return tokenPair(result);
    }

    private TokenPair refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        return tokenPair(result);
    }

    private TokenPair tokenPair(MvcResult result) throws Exception {
        String responseBody = result.getResponse().getContentAsString();
        return new TokenPair(
                responseValue(responseBody, "accessToken"),
                responseValue(responseBody, "refreshToken")
        );
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestGoogleIdentityVerifierConfiguration {

        @Bean
        @Primary
        GoogleIdentityVerifier googleIdentityVerifier() {
            return mock(GoogleIdentityVerifier.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestProtectedEndpointConfiguration {

        @Bean
        TestAuthenticationController testAuthenticationController() {
            return new TestAuthenticationController();
        }
    }

    @RestController
    static class TestAuthenticationController {

        @GetMapping("/test/authentication")
        AuthenticationResponse authentication(Authentication authentication) {
            return new AuthenticationResponse(authentication.getName());
        }
    }

    record TokenPair(String accessToken, String refreshToken) {
    }

    record AuthenticationResponse(String userId) {
    }
}
