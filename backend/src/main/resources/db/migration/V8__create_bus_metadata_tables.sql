CREATE TABLE bus_routes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(20) NOT NULL,
    external_route_id VARCHAR(255) NOT NULL,
    route_number VARCHAR(100) NOT NULL,
    city_code VARCHAR(50) NULL,
    CONSTRAINT uk_bus_routes_provider_external_route_id UNIQUE (provider, external_route_id),
    CONSTRAINT ck_bus_routes_provider CHECK (provider IN ('TAGO', 'SEOUL_BUS')),
    CONSTRAINT ck_bus_routes_provider_context CHECK (
        (provider = 'TAGO' AND city_code IS NOT NULL AND CHAR_LENGTH(TRIM(city_code)) > 0)
        OR (provider = 'SEOUL_BUS' AND city_code IS NULL)
    )
);

CREATE TABLE bus_stops (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(20) NOT NULL,
    external_stop_id VARCHAR(255) NOT NULL,
    stop_name VARCHAR(255) NOT NULL,
    latitude DECIMAL(10, 7) NULL,
    longitude DECIMAL(10, 7) NULL,
    CONSTRAINT uk_bus_stops_provider_external_stop_id UNIQUE (provider, external_stop_id),
    CONSTRAINT ck_bus_stops_provider CHECK (provider IN ('TAGO', 'SEOUL_BUS')),
    CONSTRAINT ck_bus_stops_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (
            latitude IS NOT NULL
            AND longitude IS NOT NULL
            AND latitude BETWEEN -90 AND 90
            AND longitude BETWEEN -180 AND 180
        )
    )
);

CREATE TABLE bus_route_stop_occurrences (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id BIGINT NOT NULL,
    stop_id BIGINT NOT NULL,
    stop_order INT NOT NULL,
    CONSTRAINT uk_bus_route_stop_occurrences_route_order UNIQUE (route_id, stop_order),
    CONSTRAINT fk_bus_route_stop_occurrences_route_id
        FOREIGN KEY (route_id) REFERENCES bus_routes (id) ON DELETE CASCADE,
    CONSTRAINT fk_bus_route_stop_occurrences_stop_id
        FOREIGN KEY (stop_id) REFERENCES bus_stops (id),
    CONSTRAINT ck_bus_route_stop_occurrences_stop_order CHECK (stop_order > 0)
);
