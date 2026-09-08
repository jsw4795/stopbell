package com.stopbell.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.RefreshToken;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.RefreshTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T03:00:00Z");

    @Test
    @DisplayName("Refresh Token을 발급하면 SecureRandom 원문 대신 SHA-256 해시와 30일 만료를 저장한다")
    void issue_saves_hash_and_30_day_expiration() throws Exception {
        RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RefreshTokenService refreshTokenService = new RefreshTokenService(
                refreshTokenRepository,
                mock(JwtTokenService.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        User user = new User(AuthProvider.GOOGLE, "refresh-token-service-user");

        String plaintextToken = refreshTokenService.issue(user);

        ArgumentCaptor<RefreshToken> refreshTokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        org.mockito.Mockito.verify(refreshTokenRepository).save(refreshTokenCaptor.capture());
        RefreshToken savedRefreshToken = refreshTokenCaptor.getValue();

        assertThat(plaintextToken).matches("[A-Za-z0-9_-]{43}");
        assertThat(savedRefreshToken.getUser()).isSameAs(user);
        assertThat(savedRefreshToken.getTokenHash()).isEqualTo(sha256(plaintextToken));
        assertThat(savedRefreshToken.getTokenHash()).matches("[0-9a-f]{64}");
        assertThat(savedRefreshToken.getTokenHash()).isNotEqualTo(plaintextToken);
        assertThat(savedRefreshToken.getExpiresAt()).isEqualTo(
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(30)
        );
    }

    @Test
    @DisplayName("동일 User에게 Refresh Token을 두 번 발급하면 서로 다른 원문 Token을 저장한다")
    void issue_generates_distinct_tokens() {
        RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RefreshTokenService refreshTokenService = new RefreshTokenService(
                refreshTokenRepository,
                mock(JwtTokenService.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        User user = new User(AuthProvider.GOOGLE, "multiple-refresh-token-service-user");

        String firstToken = refreshTokenService.issue(user);
        String secondToken = refreshTokenService.issue(user);

        assertThat(firstToken).isNotEqualTo(secondToken);
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
