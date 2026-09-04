package com.stopbell.user.service;

import java.time.Clock;
import java.time.Instant;

import com.stopbell.common.config.JwtProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public JwtTokenService(
            JwtEncoder jwtEncoder,
            JwtDecoder jwtDecoder,
            JwtProperties jwtProperties,
            Clock jwtClock
    ) {
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.jwtProperties = jwtProperties;
        this.clock = jwtClock;
    }

    public String createAccessToken(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("User ID must be positive");
        }

        Instant issuedAt = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuer(jwtProperties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(jwtProperties.accessTokenExpiration()))
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
        )).getTokenValue();
    }

    public Long extractUserId(String accessToken) {
        Jwt jwt = jwtDecoder.decode(accessToken);
        String subject = jwt.getSubject();

        if (subject == null || !subject.matches("[1-9]\\d*")) {
            throw new JwtException("JWT subject must be a positive StopBell User ID");
        }

        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException exception) {
            throw new JwtException("JWT subject must be a valid StopBell User ID", exception);
        }
    }
}
