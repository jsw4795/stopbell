CREATE TABLE alarm_creation_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    alarm_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_alarm_creation_requests_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_alarm_creation_requests_user_request UNIQUE (user_id, client_request_id),
    CONSTRAINT ck_alarm_creation_requests_uuid CHECK (
        REGEXP_LIKE(client_request_id, '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$', 'c')
    ),
    CONSTRAINT ck_alarm_creation_requests_lifetime CHECK (expires_at > created_at),
    INDEX idx_alarm_creation_requests_expiry (expires_at, id)
);
