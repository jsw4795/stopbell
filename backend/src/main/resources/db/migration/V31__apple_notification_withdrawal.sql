ALTER TABLE account_deletions DROP CHECK ck_account_deletions_state;
ALTER TABLE account_deletions ADD CONSTRAINT ck_account_deletions_state
    CHECK (state IN ('PENDING', 'REVOKED', 'MANUAL_REQUIRED', 'NOT_REQUIRED', 'APPLE_NOTIFIED'));

ALTER TABLE users ADD COLUMN apple_generation_issued_at DATETIME(6) NULL;

ALTER TABLE apple_notification_events DROP CHECK ck_apple_notification_subject;
ALTER TABLE apple_notification_events DROP CHECK ck_apple_notification_state;
ALTER TABLE apple_notification_events
    ADD COLUMN next_attempt_at DATETIME(6) NULL,
    ADD COLUMN completed_at DATETIME(6) NULL,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_error VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT ck_apple_notification_state CHECK (state IN ('PENDING', 'IGNORED', 'DELETED', 'SKIPPED', 'REVIEW_REQUIRED')),
    ADD CONSTRAINT ck_apple_notification_subject CHECK
        ((state IN ('PENDING', 'REVIEW_REQUIRED') AND apple_subject IS NOT NULL)
         OR (state IN ('IGNORED', 'DELETED', 'SKIPPED') AND apple_subject IS NULL)),
    ADD INDEX ix_apple_notification_retry (state, next_attempt_at, received_at),
    ADD INDEX ix_apple_notification_subject_state (apple_subject, state);
