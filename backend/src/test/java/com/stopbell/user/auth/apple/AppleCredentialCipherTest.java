package com.stopbell.user.auth.apple;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppleCredentialCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String NEXT_KEY = Base64.getEncoder().encodeToString(new byte[] {
            1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    });

    @Test
    void encryptsWithRandomNonceAndBindsCiphertextToUser() {
        var cipher = new AppleCredentialCipher("first", Map.of("first", KEY));
        String first = cipher.encrypt(1, "sensitive-apple-refresh-token");
        String second = cipher.encrypt(1, "sensitive-apple-refresh-token");
        assertThat(first).doesNotContain("sensitive-apple-refresh-token").isNotEqualTo(second);
        assertThat(cipher.decrypt(1, first)).isEqualTo("sensitive-apple-refresh-token");
        assertThatThrownBy(() -> cipher.decrypt(2, first)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void supportsOldKeysDuringRotationAndRejectsTampering() {
        var original = new AppleCredentialCipher("old", Map.of("old", KEY));
        String stored = original.encrypt(42, "refresh");
        var rotated = new AppleCredentialCipher("new", Map.of("old", KEY, "new", NEXT_KEY));
        assertThat(rotated.decrypt(42, stored)).isEqualTo("refresh");
        assertThat(rotated.encrypt(42, "refresh")).startsWith("v1.new.");
        String[] parts = stored.split("\\.");
        byte[] data = Base64.getUrlDecoder().decode(parts[3]);
        data[0] ^= 1;
        parts[3] = Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        String tampered = String.join(".", parts);
        assertThatThrownBy(() -> rotated.decrypt(42, tampered)).isInstanceOf(IllegalStateException.class);
        var missingOld = new AppleCredentialCipher("new", Map.of("new", NEXT_KEY));
        assertThatThrownBy(() -> missingOld.decrypt(42, stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidKeysAndEnvelopes() {
        assertThatThrownBy(() -> new AppleCredentialCipher("key", Map.of("key", "invalid")))
                .isInstanceOf(IllegalStateException.class);
        var cipher = new AppleCredentialCipher("key", Map.of("key", KEY));
        assertThatThrownBy(() -> cipher.decrypt(1, "not-an-envelope"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.encrypt(1, "x".repeat(8193)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(1, "x".repeat(12001)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
