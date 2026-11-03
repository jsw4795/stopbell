CREATE TABLE account_deletions (
    user_id BIGINT NOT NULL,
    state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_error VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    requested_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_account_deletions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_account_deletions_state CHECK (state IN ('PENDING', 'REVOKED', 'MANUAL_REQUIRED', 'NOT_REQUIRED')),
    INDEX idx_account_deletions_retry (next_attempt_at, user_id)
);
