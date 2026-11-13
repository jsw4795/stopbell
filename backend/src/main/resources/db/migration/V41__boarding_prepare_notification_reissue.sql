-- A Boarding PREPARE_ALIGHT Event whose Deliveries all expired or failed may be re-issued
-- (bounded per Session). Sequence 0 is the original Event.
ALTER TABLE notification_events
    ADD COLUMN reissue_sequence INT NOT NULL DEFAULT 0,
    DROP INDEX uk_notification_events_boarding_identity,
    ADD CONSTRAINT uk_notification_events_boarding_identity
        UNIQUE (boarding_session_id, boarding_event_type, reissue_sequence),
    ADD CONSTRAINT ck_notification_events_reissue_sequence
        CHECK (reissue_sequence BETWEEN 0 AND 3 AND (source_type = 'BOARDING_SESSION' OR reissue_sequence = 0));
