package com.stopbell.user.auth.apple;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** External secrets must be injected by the deployment environment. */
@ConfigurationProperties("apple.authentication")
public record AppleAuthenticationProperties(
        String clientId,
        String teamId,
        String keyId,
        String privateKeyPem,
        String credentialKeyId,
        Map<String, String> credentialKeys
) {
    @Override public String toString() { return "Apple authentication configuration [redacted]"; }
}
