package com.stopbell.user.auth.apple;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import com.stopbell.user.auth.exception.InvalidSocialCredentialException;

/** Single-process, fail-closed nonce store. Restart invalidates all outstanding challenges. */
public final class AppleNonceStore {
    private static final int MAX_OUTSTANDING = 10000;
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private final Map<String, Instant> challenges = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public AppleNonceStore(Clock clock) { this.clock = clock; }

    public synchronized String issue() {
        Instant now = clock.instant();
        challenges.values().removeIf(expiry -> !expiry.isAfter(now));
        if (challenges.size() >= MAX_OUTSTANDING) throw new IllegalStateException("Apple nonce capacity exhausted");
        String nonce;
        do {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (challenges.containsKey(nonce));
        challenges.put(nonce, now.plus(LIFETIME));
        return nonce;
    }

    public synchronized void consume(String nonce) {
        if (nonce == null || nonce.length() != 43) throw new InvalidSocialCredentialException();
        Instant expiry = challenges.remove(nonce);
        if (expiry == null || !expiry.isAfter(clock.instant())) throw new InvalidSocialCredentialException();
    }
}
