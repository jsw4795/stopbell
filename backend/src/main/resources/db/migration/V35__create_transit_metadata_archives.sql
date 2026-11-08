CREATE TABLE transit_metadata_archives (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    collection_run_id BIGINT NOT NULL,
    source_system VARCHAR(20) NOT NULL,
    logical_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_name CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    stored_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_archive_run FOREIGN KEY (collection_run_id) REFERENCES transit_metadata_collection_runs(id),
    CONSTRAINT ck_archive_source CHECK (source_system IN ('SEOUL_TDATA', 'TAGO', 'GBIS')),
    CONSTRAINT ck_archive_size CHECK (size_bytes >= 0),
    CONSTRAINT uq_archive_identity UNIQUE (collection_run_id, source_system, logical_key_hash),
    CONSTRAINT uq_archive_file UNIQUE (file_name)
);
