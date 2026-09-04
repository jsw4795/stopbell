package com.stopbell.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.stopbell.common.config.JwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

class JwtTokenServiceTest {

    private static final String SECRET = "test-secret-key-with-at-least-thirty-two-bytes";
    private static final String OTHER_SECRET = "other-secret-key-with-at-least-thirty-two-bytes";
    private static final String ISSUER = "stopbell";
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final JwtProperties jwtProperties = new JwtProperties(SECRET, ISSUER, Duration.ofHours(1));
    private final JwtTokenService jwtTokenService = new JwtTokenService(
            jwtEncoder(secretKey(SECRET)),
            jwtDecoder(secretKey(SECRET)),
            jwtProperties,
            CLOCK
    );

    @Test
    @DisplayName("Access Token을 발급하면 내부 User ID와 정책 Claim을 포함한다")
    void create_access_token_with_required_claims() {
        String accessToken = jwtTokenService.createAccessToken(42L);

        Jwt jwt = jwtDecoder(secretKey(SECRET)).decode(accessToken);

        assertThat(accessToken).isNotBlank();
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("정상 Access Token을 검증하면 StopBell User ID를 추출한다")
    void extract_user_id_from_valid_access_token() {
        String accessToken = jwtTokenService.createAccessToken(42L);

        Long userId = jwtTokenService.extractUserId(accessToken);

        assertThat(userId).isEqualTo(42L);
    }

    @Test
    @DisplayName("Payload가 변조된 Access Token은 서명 검증에 실패한다")
    void reject_access_token_with_tampered_payload() {
        String accessToken = jwtTokenService.createAccessToken(42L);
        String[] tokenParts = accessToken.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(tokenParts[1]), StandardCharsets.UTF_8);
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.replace("\"sub\":\"42\"", "\"sub\":\"43\"").getBytes(StandardCharsets.UTF_8)
        );
        String tamperedToken = tokenParts[0] + "." + tamperedPayload + "." + tokenParts[2];

        assertThatThrownBy(() -> jwtTokenService.extractUserId(tamperedToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("다른 Secret으로 서명한 Access Token은 검증에 실패한다")
    void reject_access_token_signed_with_other_secret() {
        String accessToken = createToken(secretKey(OTHER_SECRET), ISSUER, "42", NOW, NOW.plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> jwtTokenService.extractUserId(accessToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("만료된 Access Token은 검증에 실패한다")
    void reject_expired_access_token() {
        Instant issuedAt = NOW.minus(Duration.ofHours(3));
        String accessToken = createToken(secretKey(SECRET), ISSUER, "42", issuedAt, issuedAt.plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> jwtTokenService.extractUserId(accessToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("다른 issuer를 가진 Access Token은 검증에 실패한다")
    void reject_access_token_with_invalid_issuer() {
        String accessToken = createToken(secretKey(SECRET), "another-issuer", "42", NOW, NOW.plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> jwtTokenService.extractUserId(accessToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("숫자가 아닌 subject를 가진 Access Token은 검증에 실패한다")
    void reject_access_token_with_invalid_user_id_subject() {
        String accessToken = createToken(secretKey(SECRET), ISSUER, "not-a-user-id", NOW, NOW.plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> jwtTokenService.extractUserId(accessToken))
                .isInstanceOf(JwtException.class);
    }

    private JwtEncoder jwtEncoder(SecretKey secretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey));
    }

    private JwtDecoder jwtDecoder(SecretKey secretKey) {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(CLOCK);
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestampValidator,
                new JwtIssuerValidator(ISSUER)
        ));
        return jwtDecoder;
    }

    private String createToken(SecretKey secretKey, String issuer, String subject, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        return jwtEncoder(secretKey).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
        )).getTokenValue();
    }

    private SecretKey secretKey(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
