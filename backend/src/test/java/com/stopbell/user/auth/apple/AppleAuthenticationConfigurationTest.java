package com.stopbell.user.auth.apple;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AppleAuthenticationConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AppleAuthenticationConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));

    @Test
    void disabledByDefaultAndRequiresSecretsWhenEnabled() {
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(AppleAuthentication.class));
        context.withPropertyValues("apple.authentication.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void acceptsInjectedConfigurationWithoutCallingApple() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        String privateKey = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        String encryptionKey = Base64.getEncoder().encodeToString(new byte[32]);
        context.withPropertyValues(
                        "apple.authentication.enabled=true",
                        "apple.authentication.client-id=app",
                        "apple.authentication.team-id=team",
                        "apple.authentication.key-id=key",
                        "apple.authentication.private-key-pem=" + privateKey,
                        "apple.authentication.credential-key-id=current",
                        "apple.authentication.credential-keys.current=" + encryptionKey)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(AppleAuthentication.class)
                            .hasSingleBean(AppleCredentialCipher.class);
                    assertThat(ctx.getBean(AppleAuthenticationProperties.class).toString())
                            .doesNotContain(privateKey, encryptionKey);
                });
    }
}
