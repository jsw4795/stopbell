package com.stopbell.common.config;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceFeatureDefaultsTest {
    private static final Map<String, String> FEATURES = Map.of(
            "transit.monitoring.enabled", "TRANSIT_MONITORING_ENABLED",
            "notification.dispatch.enabled", "NOTIFICATION_DISPATCH_ENABLED",
            "notification.fcm.enabled", "FCM_ENABLED",
            "transit.boarding.tago-experiment-enabled", "TAGO_BOARDING_EXPERIMENT_ENABLED",
            "apple.authentication.enabled", "APPLE_AUTHENTICATION_ENABLED",
            "apple.notifications.enabled", "APPLE_NOTIFICATIONS_ENABLED",
            "apple.notifications.worker-enabled", "APPLE_NOTIFICATIONS_WORKER_ENABLED",
            "account.deletion.recovery-enabled", "ACCOUNT_DELETION_RECOVERY_ENABLED");

    private MockEnvironment environment() throws IOException {
        var environment = new MockEnvironment();
        environment.getPropertySources().addLast(new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).getFirst());
        return environment;
    }

    @Test
    void featuresDefaultOnAndCanBeExplicitlyDisabled() throws IOException {
        for (var feature : FEATURES.entrySet()) {
            var environment = environment();
            assertThat(environment.getProperty(feature.getKey(), Boolean.class)).as(feature.getKey()).isTrue();
            environment.setProperty(feature.getValue(), "false");
            assertThat(environment.getProperty(feature.getKey(), Boolean.class)).as(feature.getKey()).isFalse();
            environment.setProperty(feature.getKey(), "false");
            assertThat(environment.getProperty(feature.getKey(), Boolean.class)).as(feature.getKey()).isFalse();
        }
    }

    @Test
    void testProfileDisablesExternalWork() throws IOException {
        var environment = environment();
        environment.getPropertySources().addFirst(new YamlPropertySourceLoader()
                .load("test", new ClassPathResource("application-test.yml")).getFirst());
        for (String feature : FEATURES.keySet()) {
            assertThat(environment.getProperty(feature, Boolean.class)).as(feature)
                    .isFalse();
        }
    }
}
