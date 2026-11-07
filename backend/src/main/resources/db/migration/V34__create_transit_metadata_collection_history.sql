CREATE TABLE transit_metadata_collection_runs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    failure_stage VARCHAR(20) NULL,
    failure_code VARCHAR(32) NULL,
    failure_message VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_collection_provider CHECK (provider IN ('SEOUL_BUS', 'TAGO')),
    CONSTRAINT ck_collection_status CHECK (status IN ('COLLECTING', 'COLLECTED', 'SYNCING', 'COMPLETED', 'FAILED', 'INTERRUPTED')),
    CONSTRAINT ck_collection_terminal_time CHECK ((status IN ('COMPLETED', 'FAILED', 'INTERRUPTED') AND finished_at IS NOT NULL) OR (status IN ('COLLECTING', 'COLLECTED', 'SYNCING') AND finished_at IS NULL)),
    CONSTRAINT ck_collection_failure CHECK ((status = 'FAILED' AND failure_stage IS NOT NULL AND failure_code IS NOT NULL AND failure_message IS NOT NULL) OR (status <> 'FAILED' AND failure_stage IS NULL AND failure_code IS NULL AND failure_message IS NULL)),
    INDEX ix_collection_provider_started (provider, started_at),
    INDEX ix_collection_status (status)
);
