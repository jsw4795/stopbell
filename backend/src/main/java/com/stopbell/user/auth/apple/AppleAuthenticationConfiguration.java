package com.stopbell.user.auth.apple;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "apple.authentication", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AppleAuthenticationProperties.class)
public class AppleAuthenticationConfiguration {
    @Bean
    AppleCredentialStore appleCredentialStore(JdbcTemplate jdbc, AppleCredentialCipher cipher) {
        return new AppleCredentialStore(jdbc, cipher);
    }
    @Bean
    AppleCredentialCipher appleCredentialCipher(AppleAuthenticationProperties properties) {
        if (properties.credentialKeys() == null) throw new IllegalStateException("Apple credential keys are required");
        return new AppleCredentialCipher(properties.credentialKeyId(), properties.credentialKeys());
    }

    @Bean
    AppleClientSecret appleClientSecret(AppleAuthenticationProperties properties, Clock jwtClock) {
        return new AppleClientSecret(properties.clientId(), properties.teamId(), properties.keyId(),
                properties.privateKeyPem(), jwtClock);
    }

    @Bean
    AppleTokenRevocation appleTokenRevocation(AppleAuthenticationProperties properties, AppleClientSecret clientSecret) {
        return new AppleTokenRevocation(properties.clientId(), clientSecret);
    }

    @Bean
    AppleAuthentication appleAuthentication(AppleAuthenticationProperties properties, Clock jwtClock,
            AppleClientSecret clientSecret, AppleIdentityBoundary boundary) {
        var verifier = new AppleIdentityTokenVerifier(new AppleJwksDecoder(), properties.clientId(), jwtClock);
        return new AppleAuthentication(new AppleNonceStore(jwtClock), verifier,
                new AppleCodeExchange(properties.clientId(), clientSecret), boundary);
    }
}
