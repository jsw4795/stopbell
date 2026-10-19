CREATE TABLE boarding_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_route_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    vehicle_tracking_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    vehicle_number VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    city_code VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    route_number VARCHAR(100) NOT NULL,
    target_name VARCHAR(255) NOT NULL,
    traversal_snapshot JSON NOT NULL,
    traversal_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    state_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    checkpoint_stop_external_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    checkpoint_stop_order INT NOT NULL,
    checkpoint_index INT NOT NULL,
    checkpoint_evidence VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkpoint_direction_context VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    checkpoint_observed_at DATETIME(6) NOT NULL,
    checkpoint_provider_data_time DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    open_user_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('TRACKING', 'SUSPENDED') THEN user_id ELSE NULL END
    ) VIRTUAL,
    PRIMARY KEY (id),
    CONSTRAINT fk_boarding_sessions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_boarding_sessions_user_request UNIQUE (user_id, client_request_id),
    CONSTRAINT uk_boarding_sessions_open_user UNIQUE (open_user_id),
    CONSTRAINT ck_boarding_sessions_uuid CHECK (
        REGEXP_LIKE(client_request_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')
    ),
    CONSTRAINT ck_boarding_sessions_hashes CHECK (
        REGEXP_LIKE(request_fingerprint, '^[0-9a-f]{64}$', 'c')
        AND REGEXP_LIKE(traversal_fingerprint, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_boarding_sessions_provider CHECK (provider IN ('TAGO', 'SEOUL_BUS')),
    CONSTRAINT ck_boarding_sessions_references CHECK (
        CHAR_LENGTH(TRIM(external_route_id)) > 0 AND CHAR_LENGTH(TRIM(vehicle_tracking_id)) > 0
        AND CHAR_LENGTH(TRIM(vehicle_number)) > 0 AND CHAR_LENGTH(TRIM(route_number)) > 0
        AND CHAR_LENGTH(TRIM(target_name)) > 0
    ),
    CONSTRAINT ck_boarding_sessions_city CHECK (
        (provider = 'TAGO' AND city_code IS NOT NULL AND CHAR_LENGTH(TRIM(city_code)) > 0)
        OR (provider = 'SEOUL_BUS' AND city_code IS NULL)
    ),
    CONSTRAINT ck_boarding_sessions_status CHECK (
        status IN ('TRACKING', 'SUSPENDED', 'COMPLETED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT ck_boarding_sessions_revision CHECK (revision >= 0),
    CONSTRAINT ck_boarding_sessions_reason CHECK (
        (status = 'TRACKING' AND state_reason IS NULL)
        OR (status = 'SUSPENDED' AND state_reason IS NOT NULL AND state_reason IN (
            'TEMPORARY_PROVIDER_FAILURE', 'STALE_PROGRESS', 'VEHICLE_MISSING', 'IDENTITY_CONFLICT',
            'PROGRESS_REGRESSION', 'TRAVERSAL_AMBIGUOUS', 'CONTINUITY_GAP_EXCEEDED', 'METADATA_CHANGED'))
        OR (status = 'COMPLETED' AND state_reason IS NOT NULL AND state_reason IN ('ARRIVAL_OBSERVED', 'PASSED_OBSERVED'))
        OR (status = 'CANCELLED' AND state_reason IS NOT NULL AND state_reason = 'USER_CANCELLED')
        OR (status = 'EXPIRED' AND state_reason IS NOT NULL AND state_reason = 'SESSION_LIFETIME_EXPIRED')
    ),
    CONSTRAINT ck_boarding_sessions_checkpoint CHECK (checkpoint_stop_order > 0 AND checkpoint_index >= 0),
    CONSTRAINT ck_boarding_sessions_evidence CHECK (
        (checkpoint_evidence = 'ID_ORDER' AND checkpoint_stop_external_id IS NOT NULL
            AND CHAR_LENGTH(TRIM(checkpoint_stop_external_id)) > 0)
        OR (checkpoint_evidence = 'VERIFIED_ORDER_ONLY' AND checkpoint_stop_external_id IS NULL)
    ),
    CONSTRAINT ck_boarding_sessions_end CHECK (
        (status IN ('TRACKING', 'SUSPENDED') AND ended_at IS NULL)
        OR (status IN ('COMPLETED', 'CANCELLED', 'EXPIRED') AND ended_at IS NOT NULL)
    ),
    CONSTRAINT ck_boarding_sessions_lifetime CHECK (expires_at > created_at),
    INDEX idx_boarding_sessions_status_id (status, id)
);
