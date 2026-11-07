package com.stopbell.user.auth.apple;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppleNotificationControllerTest {
    @Test void explicitlyDisabled() {
        new ApplicationContextRunner().withUserConfiguration(AppleNotificationController.class,
                AppleNotificationController.Config.class).withPropertyValues("apple.notifications.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(AppleNotificationController.class));
    }
    @Test void enabledByDefaultRequiresAppId() {
        new ApplicationContextRunner().withUserConfiguration(AppleNotificationController.Config.class)
                .withBean(Clock.class, Clock::systemUTC)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage("Apple notification App ID is required"));
    }
    @Test void databaseFailureIsNotAcknowledged() {
        var verifier = mock(AppleNotificationVerifier.class);
        var store = mock(AppleNotificationStore.class);
        when(verifier.verify("valid")).thenReturn(new AppleNotificationVerifier.Event("id", "consent-revoked", "subject", Instant.now()));
        doThrow(new DataAccessResourceFailureException("failed")).when(store).store(any());
        var controller = new AppleNotificationController(verifier, store);
        assertThatThrownBy(() -> controller.receive(new AppleNotificationController.Request("valid")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Apple notification storage failed")
                .hasNoCause();
    }
}
