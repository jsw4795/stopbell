-- Preserve the existing preparation default; destination notification is always enabled.
ALTER TABLE boarding_sessions
    ADD COLUMN prepare_notification_enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE notification_events
    DROP CHECK ck_notification_events_source_identity,
    ADD CONSTRAINT ck_notification_events_source_identity CHECK (
        (source_type = 'ALARM'
            AND alarm_id IS NOT NULL
            AND activation_generation IS NOT NULL
            AND tracking_cycle_id IS NOT NULL
            AND event_type IS NOT NULL
            AND boarding_session_id IS NULL
            AND boarding_event_type IS NULL
            AND expires_at IS NULL)
        OR (source_type = 'BOARDING_SESSION'
            AND alarm_id IS NULL
            AND activation_generation IS NULL
            AND tracking_cycle_id IS NULL
            AND event_type IS NULL
            AND boarding_session_id IS NOT NULL AND boarding_session_id > 0
            AND boarding_event_type IS NOT NULL
            AND boarding_event_type IN ('PREPARE_ALIGHT', 'DESTINATION_REACHED')
            AND expires_at IS NOT NULL AND expires_at > observed_at)
    );
