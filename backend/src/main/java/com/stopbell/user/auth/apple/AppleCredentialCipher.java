package com.stopbell.user.auth.apple;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated encryption for Apple refresh tokens; keys are supplied externally. */
public final class AppleCredentialCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_TOKEN_BYTES = 8192;
    private static final int MAX_ENVELOPE_CHARS = 12000;
    private final String currentKeyId;
    private final Map<String, String> encodedKeys;
    private final SecureRandom random = new SecureRandom();

    public AppleCredentialCipher(String currentKeyId, Map<String, String> encodedKeys) {
        if (currentKeyId == null || !currentKeyId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid credential key identifier");
        }
        this.currentKeyId = currentKeyId;
        this.encodedKeys = Map.copyOf(encodedKeys);
        key(currentKeyId);
    }

    public String encrypt(long userId, String refreshToken) {
        if (userId <= 0 || refreshToken == null || refreshToken.isBlank()
                || refreshToken.length() > MAX_TOKEN_BYTES
                || refreshToken.getBytes(StandardCharsets.UTF_8).length > MAX_TOKEN_BYTES) {
            throw new IllegalArgumentException("Invalid credential input");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, currentKeyId, nonce, userId,
                refreshToken.getBytes(StandardCharsets.UTF_8));
        return "v1." + currentKeyId + "." + encode(nonce) + "." + encode(ciphertext);
    }

    public String decrypt(long userId, String stored) {
        if (userId <= 0 || stored == null || stored.length() > MAX_ENVELOPE_CHARS) {
            throw new IllegalArgumentException("Invalid credential input");
        }
        String[] parts = stored.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("v1")
                || !parts[1].matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid credential envelope");
        }
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(parts[2]);
            byte[] ciphertext = Base64.getUrlDecoder().decode(parts[3]);
            if (nonce.length != NONCE_BYTES || ciphertext.length < TAG_BITS / 8
                    || ciphertext.length > MAX_TOKEN_BYTES + TAG_BITS / 8) {
                throw new IllegalArgumentException("Invalid credential envelope");
            }
            return new String(crypt(Cipher.DECRYPT_MODE, parts[1], nonce, userId, ciphertext),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid credential envelope", failure);
        }
    }

    private byte[] crypt(int mode, String keyId, byte[] nonce, long userId, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key(keyId), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(("stopbell:apple:v1:" + keyId + ":" + userId).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Apple credential cryptographic operation failed", failure);
        }
    }

    private SecretKeySpec key(String keyId) {
        String value = encodedKeys.get(keyId);
        if (value == null) throw new IllegalStateException("Apple credential key is unavailable");
        if (value.length() > 48) throw new IllegalStateException("Apple credential key is invalid");
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length != 32) throw new IllegalStateException("Apple credential key must be 256 bits");
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Apple credential key is invalid", failure);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
