CREATE TABLE seoul_route_endpoints (
    route_id BIGINT PRIMARY KEY,
    external_route_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    route_number VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    start_stop_name VARCHAR(255) NOT NULL,
    end_stop_name VARCHAR(255) NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    bundle_sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
    source_page INT NOT NULL,
    CONSTRAINT fk_seoul_route_endpoints_route FOREIGN KEY (route_id) REFERENCES bus_routes(id) ON DELETE CASCADE
);
