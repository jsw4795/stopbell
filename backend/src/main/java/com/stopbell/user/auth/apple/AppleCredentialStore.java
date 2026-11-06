package com.stopbell.user.auth.apple;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
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

    public void store(User user, String refreshToken, Instant identityIssuedAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Apple credential storage requires a transaction");
        }
        if (user.getId() == null || user.getAuthProvider() != AuthProvider.APPLE) {
            throw new IllegalArgumentException("Apple user is required");
        }
        var issuedAt = LocalDateTime.ofInstant(Objects.requireNonNull(identityIssuedAt), ZoneOffset.UTC);
        var previous = jdbc.query("select identity_issued_at from apple_credentials where user_id=? for update",
                (row, index) -> row.getObject(1, LocalDateTime.class), user.getId());
        if (!previous.isEmpty() && previous.getFirst() != null && !issuedAt.isAfter(previous.getFirst())) {
            throw new InvalidSocialCredentialException();
        }
        String encrypted = cipher.encrypt(user.getId(), refreshToken);
        int updated = jdbc.update("UPDATE apple_credentials SET encrypted_refresh_token = ?, identity_issued_at = ? WHERE user_id = ?",
                encrypted, issuedAt, user.getId());
        if (updated == 0) {
            jdbc.update("INSERT INTO apple_credentials (user_id, encrypted_refresh_token, identity_issued_at) VALUES (?, ?, ?)",
                    user.getId(), encrypted, issuedAt);
        }
    }
}
