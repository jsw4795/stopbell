-- Backfill existing rows as ALARM without changing their identity or Delivery state.
ALTER TABLE notification_events
    ADD COLUMN source_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ALARM',
    ADD COLUMN boarding_session_id BIGINT NULL,
    ADD COLUMN boarding_event_type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN expires_at DATETIME(6) NULL,
    MODIFY COLUMN alarm_id BIGINT NULL,
    MODIFY COLUMN activation_generation BIGINT NULL,
    MODIFY COLUMN tracking_cycle_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY COLUMN event_type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT ck_notification_events_source CHECK (
        source_type IN ('ALARM', 'BOARDING_SESSION')
    ),
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
            AND boarding_event_type IS NOT NULL AND boarding_event_type = 'PREPARE_ALIGHT'
            AND expires_at IS NOT NULL AND expires_at > observed_at)
    ),
    ADD CONSTRAINT uk_notification_events_boarding_identity UNIQUE (boarding_session_id, boarding_event_type);

-- Require an explicit source for future writers. The default is only for the backfill.
ALTER TABLE notification_events ALTER COLUMN source_type DROP DEFAULT;
