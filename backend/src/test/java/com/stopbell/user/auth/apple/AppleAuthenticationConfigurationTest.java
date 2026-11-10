package com.stopbell.user.auth.apple;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AppleAuthenticationConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AppleAuthenticationConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(AppleIdentityBoundary.class, () -> mock(AppleIdentityBoundary.class));

    @Test
    void enabledByDefaultRequiresSecretsAndCanBeDisabled() {
        context.run(ctx -> assertThat(ctx).hasFailed());
        context.withPropertyValues("apple.authentication.enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(AppleAuthentication.class));
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
    @Test
    void acceptsExternalPrivateKeyFile(@TempDir Path directory) throws Exception {
        String key = generateKey();
        Path file = directory.resolve("AuthKey.p8");
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + key + "\n-----END PRIVATE KEY-----\n");
        configuredContext().withPropertyValues("apple.authentication.private-key-file=" + file)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(AppleAuthentication.class)
                            .hasSingleBean(AppleTokenRevocation.class);
                    assertThat(ctx.getBean(AppleClientSecret.class).create()).isNotBlank();
                    assertThat(ctx.getBean(AppleAuthenticationProperties.class).toString())
                            .doesNotContain(key, file.toString());
                });
    }

    @Test
    void rejectsMissingInvalidAndConflictingKeysWithoutExposingSecrets(@TempDir Path directory) throws Exception {
        Path invalid = directory.resolve("private-path.p8");
        String secret = "sensitive-invalid-key-material";
        Files.writeString(invalid, secret);
        Path oversized = directory.resolve("oversized.p8");
        Files.writeString(oversized, "x".repeat(8193));
        String key = generateKey();
        String[][] cases = {
                {},
                {"apple.authentication.private-key-pem=" + secret},
                {"apple.authentication.private-key-file=" + directory.resolve("missing.p8")},
                {"apple.authentication.private-key-file=" + directory},
                {"apple.authentication.private-key-file=" + invalid},
                {"apple.authentication.private-key-file=" + oversized},
                {"apple.authentication.private-key-pem=" + key, "apple.authentication.private-key-file=" + invalid}
        };
        for (String[] values : cases) {
            configuredContext().withPropertyValues(values).run(ctx -> {
                assertThat(ctx).hasFailed();
                var trace = new StringWriter();
                ctx.getStartupFailure().printStackTrace(new PrintWriter(trace));
                assertThat(trace.toString()).contains("Invalid Apple client configuration")
                        .doesNotContain(secret, key, directory.toString());
            });
        }
    }

    private ApplicationContextRunner configuredContext() {
        return context.withPropertyValues(
                "apple.authentication.enabled=true",
                "apple.authentication.client-id=app",
                "apple.authentication.team-id=team",
                "apple.authentication.key-id=key",
                "apple.authentication.credential-key-id=current",
                "apple.authentication.credential-keys.current=" + Base64.getEncoder().encodeToString(new byte[32]));
    }

    private String generateKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
    }

}
