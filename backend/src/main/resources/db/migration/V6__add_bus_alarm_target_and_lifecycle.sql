ALTER TABLE alarms
    ADD COLUMN status VARCHAR(20) NULL AFTER transit_type,
    ADD COLUMN follow_up_vehicle_tracking_id VARCHAR(255) NULL AFTER status,
    ADD COLUMN follow_up_started_at DATETIME(6) NULL AFTER follow_up_vehicle_tracking_id,
    ADD COLUMN follow_up_expires_at DATETIME(6) NULL AFTER follow_up_started_at;

UPDATE alarms
SET status = CASE
    WHEN active = TRUE THEN 'ACTIVE'
    ELSE 'INACTIVE'
END;

ALTER TABLE alarms
    MODIFY COLUMN status VARCHAR(20) NOT NULL,
    DROP COLUMN active,
    ADD CONSTRAINT ck_alarms_lifecycle CHECK (
        (
            status = 'FOLLOW_UP'
            AND follow_up_vehicle_tracking_id IS NOT NULL
            AND follow_up_started_at IS NOT NULL
            AND follow_up_expires_at IS NOT NULL
            AND follow_up_expires_at > follow_up_started_at
        )
        OR
        (
            status IN ('INACTIVE', 'ACTIVE')
            AND follow_up_vehicle_tracking_id IS NULL
            AND follow_up_started_at IS NULL
            AND follow_up_expires_at IS NULL
        )
    ),
    ADD INDEX idx_alarms_status (status);

CREATE TABLE bus_alarm_targets (
    alarm_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    external_route_id VARCHAR(255) NOT NULL,
    external_stop_id VARCHAR(255) NOT NULL,
    target_stop_order INT NOT NULL,
    route_number VARCHAR(100) NOT NULL,
    stop_name VARCHAR(255) NOT NULL,
    target_stop_latitude DECIMAL(10, 7) NULL,
    target_stop_longitude DECIMAL(10, 7) NULL,
    city_code VARCHAR(50) NULL,
    notify_one_stop_before BOOLEAN NOT NULL DEFAULT FALSE,
    predecessor_external_stop_id VARCHAR(255) NULL,
    predecessor_stop_order INT NULL,
    notify_one_stop_after BOOLEAN NOT NULL DEFAULT FALSE,
    successor_external_stop_id VARCHAR(255) NULL,
    successor_stop_order INT NULL,
    PRIMARY KEY (alarm_id),
    CONSTRAINT fk_bus_alarm_targets_alarm_id FOREIGN KEY (alarm_id) REFERENCES alarms (id) ON DELETE CASCADE,
    CONSTRAINT ck_bus_alarm_targets_provider CHECK (provider IN ('TAGO', 'SEOUL_BUS')),
    CONSTRAINT ck_bus_alarm_targets_provider_context CHECK (
        (provider = 'TAGO' AND city_code IS NOT NULL)
        OR (provider = 'SEOUL_BUS' AND city_code IS NULL)
    ),
    CONSTRAINT ck_bus_alarm_targets_target_order CHECK (target_stop_order > 0),
    CONSTRAINT ck_bus_alarm_targets_target_coordinates CHECK (
        (
            target_stop_latitude IS NULL
            AND target_stop_longitude IS NULL
        )
        OR
        (
            target_stop_latitude BETWEEN -90 AND 90
            AND target_stop_longitude BETWEEN -180 AND 180
        )
    ),
    CONSTRAINT ck_bus_alarm_targets_predecessor CHECK (
        (
            notify_one_stop_before = FALSE
            AND predecessor_external_stop_id IS NULL
            AND predecessor_stop_order IS NULL
        )
        OR
        (
            notify_one_stop_before = TRUE
            AND predecessor_external_stop_id IS NOT NULL
            AND predecessor_stop_order > 0
        )
    ),
    CONSTRAINT ck_bus_alarm_targets_successor CHECK (
        (
            notify_one_stop_after = FALSE
            AND successor_external_stop_id IS NULL
            AND successor_stop_order IS NULL
        )
        OR
        (
            notify_one_stop_after = TRUE
            AND successor_external_stop_id IS NOT NULL
            AND successor_stop_order > 0
        )
    )
);
