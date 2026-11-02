package com.stopbell.user.auth.apple;

import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Called under the owner's User row lock and inside the login transaction. */
public final class AppleCredentialStore {
    private final JdbcTemplate jdbc;
    private final AppleCredentialCipher cipher;

    public AppleCredentialStore(JdbcTemplate jdbc, AppleCredentialCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public void store(User user, String refreshToken) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Apple credential storage requires a transaction");
        }
        if (user.getId() == null || user.getAuthProvider() != AuthProvider.APPLE) {
            throw new IllegalArgumentException("Apple user is required");
        }
        String encrypted = cipher.encrypt(user.getId(), refreshToken);
        int updated = jdbc.update("UPDATE apple_credentials SET encrypted_refresh_token = ? WHERE user_id = ?",
                encrypted, user.getId());
        if (updated == 0) {
            jdbc.update("INSERT INTO apple_credentials (user_id, encrypted_refresh_token) VALUES (?, ?)",
                    user.getId(), encrypted);
        }
    }
}
