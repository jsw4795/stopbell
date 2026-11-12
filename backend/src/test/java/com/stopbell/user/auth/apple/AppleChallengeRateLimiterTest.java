package com.stopbell.user.auth.apple;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppleChallengeRateLimiterTest {
    static class MutableClock extends Clock {
        Instant instant = Instant.parse("2026-11-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    @Test
    void limitsPerKeyAndRecoversAfterWindow() {
        var clock = new MutableClock();
        var limiter = new AppleChallengeRateLimiter(clock, 3);
        for (int i = 0; i < 3; i++) limiter.check("1.1.1.1");
        assertThatThrownBy(() -> limiter.check("1.1.1.1")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
        assertThatCode(() -> limiter.check("2.2.2.2")).doesNotThrowAnyException();
        clock.instant = clock.instant.plus(Duration.ofMinutes(1));
        assertThatCode(() -> limiter.check("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void sweepsExpiredWindows() {
        var clock = new MutableClock();
        var limiter = new AppleChallengeRateLimiter(clock, 3);
        for (int i = 0; i < 100; i++) limiter.check("ip" + i);
        assertThat(limiter.size()).isEqualTo(100);
        clock.instant = clock.instant.plus(Duration.ofMinutes(2));
        limiter.check("new");
        assertThat(limiter.size()).isEqualTo(1);
    }
}
