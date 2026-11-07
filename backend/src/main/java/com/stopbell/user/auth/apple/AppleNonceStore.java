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
    private final Map<String, Challenge> challenges = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public AppleNonceStore(Clock clock) { this.clock = clock; }

    public synchronized String issue(long generation) {
        Instant now = clock.instant();
        challenges.values().removeIf(challenge -> !challenge.expiry().isAfter(now));
        if (challenges.size() >= MAX_OUTSTANDING) throw new IllegalStateException("Apple nonce capacity exhausted");
        String nonce;
        do {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (challenges.containsKey(nonce));
        challenges.put(nonce, new Challenge(now.plus(LIFETIME), generation));
        return nonce;
    }

    public synchronized long consume(String nonce) {
        if (nonce == null || nonce.length() != 43) throw new InvalidSocialCredentialException();
        Challenge challenge = challenges.remove(nonce);
        if (challenge == null || !challenge.expiry().isAfter(clock.instant())) throw new InvalidSocialCredentialException();
        return challenge.generation();
    }
    private record Challenge(Instant expiry, long generation) { }
}
