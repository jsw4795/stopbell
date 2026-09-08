package com.stopbell.user.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;

import com.stopbell.user.auth.dto.TokenResponse;
import com.stopbell.user.auth.exception.InvalidRefreshTokenException;
import com.stopbell.user.entity.RefreshToken;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final int EXPIRATION_DAYS = 30;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenService jwtTokenService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            JwtTokenService jwtTokenService,
            Clock jwtClock
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenService = jwtTokenService;
        this.clock = jwtClock;
    }

    public String issue(User user) {
        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        LocalDateTime expiresAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .plusDays(EXPIRATION_DAYS);

        refreshTokenRepository.save(new RefreshToken(user, hash(refreshToken), expiresAt));
        return refreshToken;
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }

        String tokenHash = hash(refreshToken);
        RefreshToken storedRefreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::new);

        if (!storedRefreshToken.getExpiresAt().isAfter(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))) {
            refreshTokenRepository.deleteByTokenHash(tokenHash);
            throw new InvalidRefreshTokenException();
        }

        User user = storedRefreshToken.getUser();
        if (user == null || refreshTokenRepository.deleteByTokenHash(tokenHash) != 1) {
            throw new InvalidRefreshTokenException();
        }

        String rotatedRefreshToken = issue(user);
        String accessToken = jwtTokenService.createAccessToken(user.getId());
        return new TokenResponse(accessToken, rotatedRefreshToken);
    }

    @Transactional
    public void invalidate(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        refreshTokenRepository.deleteByTokenHash(hash(refreshToken));
    }

    private String hash(String refreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }
}
