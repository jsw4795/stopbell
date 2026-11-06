CREATE TABLE apple_notification_events (
    jti_hash BINARY(32) NOT NULL PRIMARY KEY,
    event_type VARCHAR(32) NOT NULL,
    apple_subject VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    event_time DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    state VARCHAR(16) NOT NULL,
    CONSTRAINT ck_apple_notification_state CHECK (state IN ('PENDING', 'IGNORED')),
    CONSTRAINT ck_apple_notification_subject CHECK ((state = 'PENDING' AND apple_subject IS NOT NULL) OR (state = 'IGNORED' AND apple_subject IS NULL)),
    INDEX ix_apple_notification_pending (state, received_at)
);
