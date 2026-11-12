package com.stopbell.user.auth.apple;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class AppleNonceStoreTest {
    @Test
    void consumesOnlyIssuedNonceOnceAndRejectsExpired() {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        class MutableClock extends Clock {
            Instant instant = now;
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return instant; }
        }
        var clock = new MutableClock();
        var store = new AppleNonceStore(clock);
        String nonce = store.issue(7);
        assertThat(store.consume(nonce)).isEqualTo(7);
        assertThatThrownBy(() -> store.consume(nonce)).isInstanceOf(InvalidSocialCredentialException.class);
        assertThatThrownBy(() -> store.consume("a".repeat(43))).isInstanceOf(InvalidSocialCredentialException.class);
        String expired = store.issue(7);
        clock.instant = now.plus(Duration.ofMinutes(5));
        assertThatThrownBy(() -> store.consume(expired)).isInstanceOf(InvalidSocialCredentialException.class);
    }

    @Test
    void mapsCapacityExhaustionToRateLimitedAndRecoversAfterExpiry() {
        var clock = new Clock() {
            Instant instant = Instant.parse("2026-11-01T00:00:00Z");
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return instant; }
        };
        var store = new AppleNonceStore(clock);
        for (int i = 0; i < 10000; i++) store.issue(1);
        assertThatThrownBy(() -> store.issue(1)).isInstanceOfSatisfying(com.stopbell.common.error.ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(com.stopbell.common.error.ErrorCode.RATE_LIMITED));
        clock.instant = clock.instant.plus(Duration.ofMinutes(5));
        assertThat(store.issue(1)).hasSize(43);
    }
}
