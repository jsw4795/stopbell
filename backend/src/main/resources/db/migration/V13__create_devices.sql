CREATE TABLE devices (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    installation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    installation_credential_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    platform VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_push_target_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    registration_revision BIGINT NOT NULL,
    ownership_generation BIGINT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL,
    last_registered_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_devices_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_devices_installation_id UNIQUE (installation_id),
    CONSTRAINT uk_devices_current_push_target_id UNIQUE (current_push_target_id),
    CONSTRAINT ck_devices_registration_revision CHECK (registration_revision >= 0),
    CONSTRAINT ck_devices_ownership_generation CHECK (ownership_generation >= 0),
    CONSTRAINT ck_devices_platform CHECK (platform IN ('IOS', 'ANDROID')),
    CONSTRAINT ck_devices_enabled_target CHECK (
        (enabled = TRUE AND current_push_target_id IS NOT NULL AND CHAR_LENGTH(current_push_target_id) > 0)
        OR (enabled = FALSE AND current_push_target_id IS NULL)
    ),
    INDEX idx_devices_user_enabled (user_id, enabled)
);
