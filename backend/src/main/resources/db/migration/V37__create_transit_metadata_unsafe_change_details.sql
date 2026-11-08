ALTER TABLE transit_metadata_diff_analyses
    ADD COLUMN review_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;

CREATE TABLE transit_metadata_unsafe_change_details (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    collection_run_id BIGINT NOT NULL,
    sequence_number INT NOT NULL,
    reason VARCHAR(32) NOT NULL,
    route_id VARCHAR(255) NULL,
    stop_id VARCHAR(255) NULL,
    stop_order INT NULL,
    replacement_stop_id VARCHAR(255) NULL,
    CONSTRAINT fk_unsafe_detail_analysis FOREIGN KEY (collection_run_id) REFERENCES transit_metadata_diff_analyses(collection_run_id),
    CONSTRAINT uq_unsafe_detail_sequence UNIQUE (collection_run_id, sequence_number),
    CONSTRAINT ck_unsafe_detail_reason CHECK (reason IN ('ROUTE_REMOVAL', 'OCCURRENCE_REMOVAL', 'STOP_CLEANUP', 'SHARED_STOP_CONFLICT'))
);

CREATE TABLE transit_metadata_unsafe_change_variants (
    detail_id BIGINT NOT NULL,
    sequence_number INT NOT NULL,
    route_id VARCHAR(255) NOT NULL,
    stop_order INT NOT NULL,
    stop_name VARCHAR(255) NOT NULL,
    latitude VARCHAR(255) NULL,
    longitude VARCHAR(255) NULL,
    PRIMARY KEY (detail_id, sequence_number),
    CONSTRAINT fk_unsafe_variant_detail FOREIGN KEY (detail_id) REFERENCES transit_metadata_unsafe_change_details(id)
);
