ALTER TABLE bus_alarm_targets
    DROP CHECK ck_bus_alarm_targets_target_coordinates,
    DROP CHECK ck_bus_alarm_targets_predecessor,
    DROP CHECK ck_bus_alarm_targets_successor,
    ADD CONSTRAINT ck_bus_alarm_targets_target_coordinates CHECK (
        (
            target_stop_latitude IS NULL
            AND target_stop_longitude IS NULL
        )
        OR
        (
            target_stop_latitude IS NOT NULL
            AND target_stop_longitude IS NOT NULL
            AND target_stop_latitude BETWEEN -90 AND 90
            AND target_stop_longitude BETWEEN -180 AND 180
        )
    ),
    ADD CONSTRAINT ck_bus_alarm_targets_predecessor CHECK (
        (
            notify_one_stop_before = FALSE
            AND predecessor_external_stop_id IS NULL
            AND predecessor_stop_order IS NULL
        )
        OR
        (
            notify_one_stop_before = TRUE
            AND predecessor_external_stop_id IS NOT NULL
            AND predecessor_stop_order IS NOT NULL
            AND predecessor_stop_order > 0
        )
    ),
    ADD CONSTRAINT ck_bus_alarm_targets_successor CHECK (
        (
            notify_one_stop_after = FALSE
            AND successor_external_stop_id IS NULL
            AND successor_stop_order IS NULL
        )
        OR
        (
            notify_one_stop_after = TRUE
            AND successor_external_stop_id IS NOT NULL
            AND successor_stop_order IS NOT NULL
            AND successor_stop_order > 0
        )
    );
