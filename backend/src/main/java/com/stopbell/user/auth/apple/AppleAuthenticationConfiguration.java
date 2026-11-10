package com.stopbell.user.auth.apple;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                privateKey(properties), jwtClock);
    }

    private static String privateKey(AppleAuthenticationProperties properties) {
        String pem = properties.privateKeyPem();
        String file = properties.privateKeyFile();
        if (file == null || file.isBlank()) return pem;
        if (pem != null && !pem.isBlank()) throw new IllegalArgumentException("Invalid Apple client configuration");
        try (InputStream input = Files.newInputStream(Path.of(file))) {
            byte[] bytes = input.readNBytes(8193);
            if (bytes.length > 8192) throw new IllegalArgumentException("Invalid Apple client configuration");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid Apple client configuration");
        }
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
