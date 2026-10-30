package com.stopbell.user.auth.config;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    private static final int HS256_MINIMUM_KEY_LENGTH_BYTES = 32;

    @Bean
    SecretKey jwtSecretKey(JwtProperties jwtProperties) {
        byte[] secretBytes = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < HS256_MINIMUM_KEY_LENGTH_BYTES) {
            throw new IllegalStateException("JWT secret must be at least 256 bits for HS256");
        }

        return new SecretKeySpec(secretBytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties jwtProperties, Clock jwtClock) {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        timestampValidator.setAllowEmptyExpiryClaim(false);
        timestampValidator.setClock(jwtClock);
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestampValidator,
                new JwtIssuerValidator(jwtProperties.issuer()),
                this::validateSubject
        ));
        return jwtDecoder;
    }

    private OAuth2TokenValidatorResult validateSubject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject != null && subject.matches("[1-9]\\d*") && hasStringSubject(jwt)) {
            try {
                Long.parseLong(subject);
                return OAuth2TokenValidatorResult.success();
            } catch (NumberFormatException exception) {
                // A positive decimal string can still exceed the StopBell User ID range.
            }
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "JWT subject is invalid.", null));
    }

    private boolean hasStringSubject(Jwt jwt) {
        try {
            // Nimbus normalizes numeric sub claims to strings. Check the signed payload's original type, too.
            var claims = SignedJWT.parse(jwt.getTokenValue()).getPayload().toJSONObject();
            return claims != null && claims.get("sub") instanceof String;
        } catch (ParseException exception) {
            return false;
        }
    }

    @Bean
    Clock jwtClock() {
        return Clock.systemUTC();
    }
}
