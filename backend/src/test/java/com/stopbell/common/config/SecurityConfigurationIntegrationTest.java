package com.stopbell.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.SecretKey;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;

import com.stopbell.common.error.SecurityErrorHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import com.stopbell.user.auth.service.JwtTokenService;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.user.repository.UserRepository;
import com.stopbell.user.repository.RefreshTokenRepository;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import com.stopbell.transit.repository.BusStopRepository;
import com.stopbell.transit.repository.BusMetadataSyncStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;

import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.notification.repository.NotificationEventRepository;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({
        SecurityConfigurationIntegrationTest.TestEndpointConfiguration.class,
        SecurityConfigurationIntegrationTest.TestRepositoryConfiguration.class,
        SecurityConfigurationIntegrationTest.TestDeniedEndpointConfiguration.class
})
class SecurityConfigurationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private SecretKey jwtSecretKey;

    @Test
    @DisplayName("유효한 Access Token 요청은 JWT subject를 Authentication name으로 전달한다")
    void authenticate_valid_access_token() throws Exception {
        String accessToken = jwtTokenService.createAccessToken(42L);

        mockMvc.perform(get("/test/authentication")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("42"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("Bearer Token이 없으면 보호 Endpoint에 접근할 수 없다")
    void reject_request_without_bearer_token() throws Exception {
        mockMvc.perform(get("/test/authentication"))
                .andExpect(status().isUnauthorized())
                .andExpect(this::assertUnauthenticated);
    }

    @Test
    @DisplayName("변조된 Access Token은 보호 Endpoint에 접근할 수 없다")
    void reject_tampered_access_token() throws Exception {
        String accessToken = jwtTokenService.createAccessToken(42L);

        mockMvc.perform(get("/test/authentication")
                        .header("Authorization", "Bearer " + tamperPayload(accessToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(this::assertUnauthenticated);
    }

    @Test
    @DisplayName("만료된 Access Token은 보호 Endpoint에 접근할 수 없다")
    void reject_expired_access_token() throws Exception {
        String accessToken = createAccessToken("stopbell", Instant.now().minusSeconds(120), Instant.now().minusSeconds(61));

        mockMvc.perform(get("/test/authentication")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(this::assertUnauthenticated);
    }

    @Test
    @DisplayName("잘못된 issuer Access Token은 보호 Endpoint에 접근할 수 없다")
    void reject_access_token_with_invalid_issuer() throws Exception {
        String accessToken = createAccessToken("another-issuer", Instant.now(), Instant.now().plusSeconds(3600));

        mockMvc.perform(get("/test/authentication")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(this::assertUnauthenticated);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-1", "01", "+1", "1.0", "1e2", " 1", "user-secret-marker", "9223372036854775808"})
    @DisplayName("유효하게 서명했어도 양의 Long 문자열이 아닌 subject는 인증 단계에서 거부한다")
    void reject_invalid_subject_before_authentication(String subject) throws Exception {
        var claims = JwtClaimsSet.builder().issuer("stopbell").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        if (subject != null) claims.subject(subject);
        String token = encode(claims.build());
        // This endpoint only reads Authentication.name; it never calls extractUserId.
        MvcResult result = mockMvc.perform(get("/test/authentication")
                        .header("Authorization", "Bearer " + token))
                .andExpect(this::assertUnauthenticated).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token, "user-secret-marker");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer", "Bearer ", "Bearer private token", "Bearer not-a-jwt"})
    @DisplayName("잘못된 Bearer header와 JWT 형식도 원문 없이 JSON 401을 반환한다")
    void reject_malformed_bearer(String authorization) throws Exception {
        mockMvc.perform(get("/test/authentication").header("Authorization", authorization))
                .andExpect(this::assertUnauthenticated);
    }

    @Test
    @DisplayName("필수 exp가 없는 서명된 JWT는 JSON 401로 거부한다")
    void reject_missing_expiry() throws Exception {
        String token = encode(JwtClaimsSet.builder().issuer("stopbell").subject("42")
                .issuedAt(Instant.now()).build());
        mockMvc.perform(get("/test/authentication").header("Authorization", "Bearer " + token))
                .andExpect(this::assertUnauthenticated);
    }

    @ParameterizedTest
    @ValueSource(longs = {42L, Long.MAX_VALUE})
    @DisplayName("Nimbus가 문자열로 정규화하는 JSON 숫자형 subject도 인증 전에 거부한다")
    void reject_numeric_subject_claim(long subject) throws Exception {
        long now = Instant.now().getEpochSecond();
        JWSObject signed = new JWSObject(new JWSHeader(JWSAlgorithm.HS256),
                new Payload(Map.of("iss", "stopbell", "iat", now, "exp", now + 3600, "sub", subject)));
        signed.sign(new MACSigner(jwtSecretKey));
        mockMvc.perform(get("/test/authentication").header("Authorization", "Bearer " + signed.serialize()))
                .andExpect(this::assertUnauthenticated);
    }

    @Test
    @DisplayName("최대 Long subject는 기존 인증 경계를 정상 통과한다")
    void accept_maximum_user_id() throws Exception {
        String token = jwtTokenService.createAccessToken(Long.MAX_VALUE);
        mockMvc.perform(get("/test/authentication").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(Long.toString(Long.MAX_VALUE)));
    }

    @Test
    @DisplayName("인증된 접근 거부는 JSON 403이며 같은 조건의 미인증 요청은 401이다")
    void distinguish_access_denied_from_unauthenticated() throws Exception {
        mockMvc.perform(get("/test/denied")).andExpect(this::assertUnauthenticated);
        MvcResult result = mockMvc.perform(get("/test/denied")
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(42L)))
                .andExpect(status().isForbidden()).andReturn();
        assertError(result, 403, "ACCESS_DENIED", "Access is denied.");
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"insufficient_scope\"");
    }

    private void assertUnauthenticated(MvcResult result) throws Exception {
        assertError(result, 401, "UNAUTHENTICATED", "Authentication is required or invalid.");
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer")
                .doesNotContain("error_description", "private", "JWT subject");
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    private void assertError(MvcResult result, int status, String code, String message) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/json");
        var json = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(json.get("code").asString()).isEqualTo(code);
        assertThat(json.get("message").asString()).isEqualTo(message);
    }

    private String encode(JwtClaimsSet claims) {
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private String createAccessToken(String issuer, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("42")
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
        )).getTokenValue();
    }

    private String tamperPayload(String accessToken) {
        String[] tokenParts = accessToken.split("\\.");
        byte[] payload = Base64.getUrlDecoder().decode(tokenParts[1]);
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(payload, StandardCharsets.UTF_8)
                        .replace("\"sub\":\"42\"", "\"sub\":\"43\"")
                        .getBytes(StandardCharsets.UTF_8)
        );
        return tokenParts[0] + "." + tamperedPayload + "." + tokenParts[2];
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEndpointConfiguration {

        @Bean
        TestAuthenticationController testAuthenticationController() {
            return new TestAuthenticationController();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestRepositoryConfiguration {

        @Bean
        UserRepository userRepository() {
            return mock(UserRepository.class);
        }

        @Bean
        BoardingSessionRepository boardingSessionRepository() {
            return mock(BoardingSessionRepository.class);
        }

        @Bean
        AlarmRepository alarmRepository() {
            return mock(AlarmRepository.class);
        }

        @Bean
        RefreshTokenRepository refreshTokenRepository() {
            return mock(RefreshTokenRepository.class);
        }

        @Bean
        BusRouteRepository busRouteRepository() {
            return mock(BusRouteRepository.class);
        }

        @Bean
        BusStopRepository busStopRepository() {
            return mock(BusStopRepository.class);
        }

        @Bean
        BusRouteStopOccurrenceRepository busRouteStopOccurrenceRepository() {
            return mock(BusRouteStopOccurrenceRepository.class);
        }

        @Bean
        BusMetadataSyncStateRepository busMetadataSyncStateRepository() {
            return mock(BusMetadataSyncStateRepository.class);
        }
        
        @Bean
        DeviceRepository deviceRepository() {
            return mock(DeviceRepository.class);
        }

        @Bean
        NotificationDeliveryRepository notificationDeliveryRepository() {
            return mock(NotificationDeliveryRepository.class);
        }

        @Bean
        NotificationEventRepository notificationEventRepository() {
            return mock(NotificationEventRepository.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDeniedEndpointConfiguration {
        @Bean
        @Order(0)
        SecurityFilterChain deniedEndpoint(HttpSecurity http, SecurityErrorHandler errors) throws Exception {
            return http.securityMatcher("/test/denied")
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
                    .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                    .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                            .authenticationEntryPoint(errors).accessDeniedHandler(errors))
                    .build();
        }
    }

    @RestController
    static class TestAuthenticationController {

        @GetMapping("/test/authentication")
        AuthenticationResponse authentication(Authentication authentication) {
            return new AuthenticationResponse(authentication.getName());
        }
    }

    record AuthenticationResponse(String userId) {
    }
}
