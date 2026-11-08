CREATE TABLE transit_metadata_diff_analyses (
    collection_run_id BIGINT NOT NULL PRIMARY KEY,
    provider VARCHAR(20) NOT NULL,
    baseline_type VARCHAR(32) NOT NULL,
    previous_complete_sync_at DATETIME(6) NULL,
    analyzed_at DATETIME(6) NOT NULL,
    result_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    summary JSON NOT NULL,
    CONSTRAINT fk_diff_analysis_run FOREIGN KEY (collection_run_id) REFERENCES transit_metadata_collection_runs(id),
    CONSTRAINT ck_diff_analysis_provider CHECK (provider IN ('SEOUL_BUS', 'TAGO')),
    CONSTRAINT ck_diff_analysis_baseline CHECK (baseline_type = 'CURRENT_DB_BEFORE_APPLY')
);
