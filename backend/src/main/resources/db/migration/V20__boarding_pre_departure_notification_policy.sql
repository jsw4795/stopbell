-- Never reinterpret existing sessions, fingerprints, Events or pending Deliveries.
-- NULL = legacy optional preparation + mandatory destination notification.
-- New application requests explicitly store 1 or 2; there is deliberately no backfill/default.
ALTER TABLE boarding_sessions
    ADD COLUMN notification_offset_stops INT NULL,
    ADD CONSTRAINT ck_boarding_sessions_notification_offset CHECK (
        notification_offset_stops IS NULL
        OR (notification_offset_stops IN (1, 2) AND prepare_notification_enabled = TRUE)
    );
