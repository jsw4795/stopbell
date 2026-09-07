package com.stopbell.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.stopbell.user.service.JwtTokenService;
import com.stopbell.user.repository.UserRepository;
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

@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({
        SecurityConfigurationIntegrationTest.TestEndpointConfiguration.class,
        SecurityConfigurationIntegrationTest.TestRepositoryConfiguration.class
})
class SecurityConfigurationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

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
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("변조된 Access Token은 보호 Endpoint에 접근할 수 없다")
    void reject_tampered_access_token() throws Exception {
        String accessToken = jwtTokenService.createAccessToken(42L);

        mockMvc.perform(get("/test/authentication")
                        .header("Authorization", "Bearer " + tamperPayload(accessToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("만료된 Access Token은 보호 Endpoint에 접근할 수 없다")
    void reject_expired_access_token() throws Exception {
        String accessToken = createAccessToken("stopbell", Instant.now().minusSeconds(120), Instant.now().minusSeconds(61));

        mockMvc.perform(get("/test/authentication")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("잘못된 issuer Access Token은 보호 Endpoint에 접근할 수 없다")
    void reject_access_token_with_invalid_issuer() throws Exception {
        String accessToken = createAccessToken("another-issuer", Instant.now(), Instant.now().plusSeconds(3600));

        mockMvc.perform(get("/test/authentication")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
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
