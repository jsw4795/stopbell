package com.stopbell.notification.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;

final class InstallationCredential {

    private InstallationCredential() {
    }

    static String hash(String credential) {
        if (credential == null || !credential.matches("[A-Za-z0-9_-]{43}")) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        byte[] decoded = Base64.getUrlDecoder().decode(credential);
        try {
            if (decoded.length != 32
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(credential)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(decoded));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    static void verify(String storedHash, String suppliedHash) {
        if (!MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.US_ASCII), suppliedHash.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new ApiException(ErrorCode.DEVICE_CREDENTIAL_INVALID);
        }
    }
}
