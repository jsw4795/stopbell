ALTER TABLE transit_metadata_diff_analyses
    ADD COLUMN fingerprint_version TINYINT NOT NULL DEFAULT 1;

CREATE TABLE transit_metadata_change_approvals (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_run_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    result_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approved_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    approval_note VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    apply_run_id BIGINT NULL,
    claimed_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    CONSTRAINT fk_change_approval_source FOREIGN KEY (source_run_id) REFERENCES transit_metadata_collection_runs(id),
    CONSTRAINT fk_change_approval_apply FOREIGN KEY (apply_run_id) REFERENCES transit_metadata_collection_runs(id),
    CONSTRAINT uq_change_approval_source UNIQUE (source_run_id),
    CONSTRAINT uq_change_approval_apply UNIQUE (apply_run_id),
    CONSTRAINT ck_change_approval_provider CHECK (provider IN ('SEOUL_BUS', 'TAGO')),
    CONSTRAINT ck_change_approval_status CHECK (status IN ('APPROVED', 'CLAIMED', 'APPLIED', 'FAILED', 'UNCERTAIN', 'REVOKED')),
    CONSTRAINT ck_change_approval_expiry CHECK (expires_at > approved_at),
    CONSTRAINT ck_change_approval_lifecycle CHECK (
        (status = 'APPROVED' AND apply_run_id IS NULL AND claimed_at IS NULL AND finished_at IS NULL)
        OR (status = 'REVOKED' AND apply_run_id IS NULL AND claimed_at IS NULL AND finished_at IS NOT NULL)
        OR (status = 'CLAIMED' AND apply_run_id IS NOT NULL AND claimed_at IS NOT NULL AND finished_at IS NULL)
        OR (status IN ('APPLIED', 'FAILED', 'UNCERTAIN') AND apply_run_id IS NOT NULL AND claimed_at IS NOT NULL AND finished_at IS NOT NULL)
    )
);
