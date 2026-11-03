CREATE TABLE account_deletion_receipts (
    receipt_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pending_user_id BIGINT NULL,
    state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (receipt_hash),
    INDEX idx_deletion_receipt_user (pending_user_id),
    INDEX idx_deletion_receipt_expiry (completed_at),
    CONSTRAINT ck_deletion_receipt_state CHECK (state IN ('PENDING', 'DELETED', 'MANUAL_REQUIRED'))
);
