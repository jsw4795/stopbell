-- Stop monitoring and legacy History writers before applying this migration.
-- A surviving FOLLOW_UP has no recoverable original UUID: fail BEFORE persistent DDL.
CREATE TEMPORARY TABLE task707_follow_up_guard (
    follow_up_count BIGINT NOT NULL,
    CONSTRAINT ck_task707_no_legacy_follow_up CHECK (follow_up_count = 0)
);
INSERT INTO task707_follow_up_guard SELECT COUNT(*) FROM alarms WHERE status = 'FOLLOW_UP';
DROP TEMPORARY TABLE task707_follow_up_guard;

ALTER TABLE alarms
    ADD COLUMN follow_up_tracking_cycle_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    DROP CHECK ck_alarms_lifecycle,
    ADD CONSTRAINT ck_alarms_lifecycle CHECK (
        (status = 'FOLLOW_UP'
            AND follow_up_vehicle_tracking_id IS NOT NULL
            AND follow_up_tracking_cycle_id IS NOT NULL
            AND follow_up_started_at IS NOT NULL
            AND follow_up_expires_at IS NOT NULL
            AND follow_up_expires_at > follow_up_started_at)
        OR (status IN ('INACTIVE', 'ACTIVE')
            AND follow_up_vehicle_tracking_id IS NULL
            AND follow_up_tracking_cycle_id IS NULL
            AND follow_up_started_at IS NULL
            AND follow_up_expires_at IS NULL)
    ),
    ADD CONSTRAINT ck_alarms_follow_up_tracking_cycle_id CHECK (
        follow_up_tracking_cycle_id IS NULL OR REGEXP_LIKE(follow_up_tracking_cycle_id,
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')
    );

CREATE TABLE notification_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    alarm_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    activation_generation BIGINT NOT NULL,
    tracking_cycle_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    event_detected_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    current_stop_external_id VARCHAR(255) NULL,
    current_stop_name VARCHAR(255) NULL,
    current_stop_order INT NULL,
    latitude DECIMAL(10,7) NULL,
    longitude DECIMAL(10,7) NULL,
    provider_data_time DATETIME(6) NULL,
    stops_past_target INT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_events_logical_identity
        UNIQUE (alarm_id, activation_generation, tracking_cycle_id, event_type),
    CONSTRAINT fk_notification_events_user_id FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT ck_notification_events_activation_generation CHECK (activation_generation >= 0),
    CONSTRAINT ck_notification_events_event_type
        CHECK (event_type IN ('ONE_STOP_BEFORE', 'ARRIVED', 'PASSED', 'ONE_STOP_AFTER')),
    CONSTRAINT ck_notification_events_tracking_cycle_id CHECK (REGEXP_LIKE(tracking_cycle_id,
        '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')),
    CONSTRAINT ck_notification_events_position CHECK (
        (latitude IS NULL AND longitude IS NULL) OR (latitude IS NOT NULL AND longitude IS NOT NULL)
    ),
    CONSTRAINT ck_notification_events_stops_past_target
        CHECK (stops_past_target IS NULL OR stops_past_target > 0)
);

CREATE TABLE notification_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    notification_event_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    recipient_ownership_generation BIGINT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    last_attempt_at DATETIME(6) NULL,
    last_attempt_registration_revision BIGINT NULL,
    last_provider_result VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider_accepted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_deliveries_event_device UNIQUE (notification_event_id, device_id),
    CONSTRAINT fk_notification_deliveries_event_id
        FOREIGN KEY (notification_event_id) REFERENCES notification_events(id),
    CONSTRAINT fk_notification_deliveries_device_id FOREIGN KEY (device_id) REFERENCES devices(id),
    CONSTRAINT ck_notification_deliveries_status CHECK (status IN ('PENDING', 'ACCEPTED', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_notification_deliveries_counts CHECK (attempt_count >= 0 AND recipient_ownership_generation >= 0),
    CONSTRAINT ck_notification_deliveries_schedule CHECK (
        (status = 'PENDING' AND next_attempt_at IS NOT NULL AND next_attempt_at <= expires_at)
        OR (status <> 'PENDING' AND next_attempt_at IS NULL)
    ),
    CONSTRAINT ck_notification_deliveries_attempt CHECK (
        (attempt_count = 0 AND last_attempt_at IS NULL AND last_attempt_registration_revision IS NULL
            AND last_provider_result IS NULL)
        OR (attempt_count > 0 AND last_attempt_at IS NOT NULL AND last_attempt_registration_revision IS NOT NULL
            AND last_attempt_registration_revision >= 0)
    ),
    CONSTRAINT ck_notification_deliveries_provider_result CHECK (
        last_provider_result IS NULL OR last_provider_result IN
            ('ACCEPTED', 'INVALID_TARGET', 'RETRYABLE', 'CONFIGURATION', 'PERMANENT_REQUEST', 'AMBIGUOUS_TIMEOUT')
    ),
    CONSTRAINT ck_notification_deliveries_acceptance CHECK (
        (status = 'ACCEPTED' AND attempt_count > 0 AND last_provider_result IS NOT NULL
            AND last_provider_result = 'ACCEPTED' AND provider_accepted_at IS NOT NULL AND last_failure_code IS NULL)
        OR (status <> 'ACCEPTED' AND provider_accepted_at IS NULL
            AND (last_provider_result IS NULL OR last_provider_result <> 'ACCEPTED'))
    ),
    CONSTRAINT ck_notification_deliveries_failure_code CHECK (
        (status IN ('FAILED', 'EXPIRED') AND last_failure_code IS NOT NULL AND CHAR_LENGTH(last_failure_code) > 0)
        OR (status NOT IN ('FAILED', 'EXPIRED') AND (last_failure_code IS NULL OR CHAR_LENGTH(last_failure_code) > 0))
    ),
    INDEX idx_notification_deliveries_pending_due (status, next_attempt_at, id),
    INDEX idx_notification_deliveries_device_id (device_id)
);

-- Empty legacy tables can be removed. Nonempty tables remain unchanged except
-- for removing the Alarm cascade FK so later Alarm deletes cannot erase history.
-- Do not invent generation/cycle/recipient data to convert legacy records.
SET @task707_history_ddl = IF((SELECT COUNT(*) FROM notification_history) = 0,
    'DROP TABLE notification_history',
    'ALTER TABLE notification_history DROP FOREIGN KEY fk_notification_history_alarm_id');
PREPARE task707_history_statement FROM @task707_history_ddl;
EXECUTE task707_history_statement;
DEALLOCATE PREPARE task707_history_statement;
SET @task707_history_ddl = NULL;
