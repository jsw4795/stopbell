ALTER TABLE bus_routes ADD COLUMN route_type VARCHAR(24) NULL;

CREATE TABLE transit_source_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_system VARCHAR(20) NOT NULL,
    data_kind VARCHAR(24) NOT NULL,
    source_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    route_id BIGINT NULL,
    stop_id BIGINT NULL,
    occurrence_id BIGINT NULL,
    raw_payload JSON NOT NULL,
    source_version VARCHAR(100) NULL,
    collected_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_transit_source_metadata UNIQUE (source_system, data_kind, source_key),
    CONSTRAINT ck_transit_source_system CHECK (source_system IN ('SEOUL_TDATA', 'TAGO', 'GBIS')),
    CONSTRAINT ck_transit_source_kind CHECK (data_kind IN ('ROUTE', 'STOP', 'OCCURRENCE')),
    CONSTRAINT fk_transit_source_route FOREIGN KEY (route_id) REFERENCES bus_routes(id) ON DELETE SET NULL,
    CONSTRAINT fk_transit_source_stop FOREIGN KEY (stop_id) REFERENCES bus_stops(id) ON DELETE SET NULL,
    CONSTRAINT fk_transit_source_occurrence FOREIGN KEY (occurrence_id) REFERENCES bus_route_stop_occurrences(id) ON DELETE SET NULL
);
