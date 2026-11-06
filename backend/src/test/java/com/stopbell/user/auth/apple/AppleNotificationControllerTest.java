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
    @Test void disabledByDefault() {
        new ApplicationContextRunner().withUserConfiguration(AppleNotificationController.class,
                AppleNotificationController.Config.class).run(context ->
                assertThat(context).doesNotHaveBean(AppleNotificationController.class));
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
