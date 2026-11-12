package com.stopbell.user.auth.apple;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;

/** Single-process fixed-window limiter keyed by client address. Expired windows are swept once per window. */
public final class AppleChallengeRateLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_KEYS = 50000;
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    private final int limit;
    private Instant nextSweep = Instant.MIN;

    public AppleChallengeRateLimiter(Clock clock, int limit) {
        this.clock = clock;
        this.limit = limit;
    }

    public synchronized void check(String key) {
        Instant now = clock.instant();
        if (!now.isBefore(nextSweep) || windows.size() >= MAX_KEYS) {
            windows.values().removeIf(window -> !window.end.isAfter(now));
            nextSweep = now.plus(WINDOW);
        }
        Window window = windows.get(key);
        if (window == null || !window.end.isAfter(now)) {
            if (window == null && windows.size() >= MAX_KEYS) throw new ApiException(ErrorCode.RATE_LIMITED);
            window = new Window(now.plus(WINDOW));
            windows.put(key, window);
        }
        if (++window.count > limit) throw new ApiException(ErrorCode.RATE_LIMITED);
    }

    int size() { return windows.size(); }

    private static final class Window {
        final Instant end;
        int count;
        Window(Instant end) { this.end = end; }
    }
}
