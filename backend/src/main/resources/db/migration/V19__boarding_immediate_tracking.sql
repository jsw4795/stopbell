-- TRACKING means observing the selected vehicle, independently of progress readiness.
-- V18 stays immutable. Preserve every session, checkpoint, Event and Delivery.
ALTER TABLE boarding_sessions
    DROP CHECK ck_boarding_sessions_reason;

UPDATE boarding_sessions
SET status = CASE WHEN state_reason = 'METADATA_CHANGED' THEN 'SUSPENDED' ELSE 'TRACKING' END,
    revision = revision + 1
WHERE status = 'VERIFYING';

ALTER TABLE boarding_sessions
    DROP CHECK ck_boarding_sessions_status,
    DROP CHECK ck_boarding_sessions_end,
    DROP INDEX uk_boarding_sessions_open_user,
    DROP COLUMN open_user_id,
    ADD COLUMN open_user_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('TRACKING', 'SUSPENDED') THEN user_id ELSE NULL END
    ) VIRTUAL,
    ADD CONSTRAINT uk_boarding_sessions_open_user UNIQUE (open_user_id),
    ADD CONSTRAINT ck_boarding_sessions_status CHECK (
        status IN ('TRACKING', 'SUSPENDED', 'COMPLETED', 'CANCELLED', 'EXPIRED')
    ),
    ADD CONSTRAINT ck_boarding_sessions_reason CHECK (
        (status = 'TRACKING' AND (
            (state_reason IS NULL AND checkpoint_evidence <> 'UNVERIFIED_REFERENCE')
            OR (state_reason IS NOT NULL AND state_reason IN ('TEMPORARY_PROVIDER_FAILURE', 'STALE_PROGRESS'))
            OR (state_reason IS NOT NULL AND state_reason = 'BASELINE_UNVERIFIED' AND checkpoint_evidence = 'UNVERIFIED_REFERENCE')))
        OR (status = 'SUSPENDED' AND state_reason IS NOT NULL AND state_reason IN (
            'TEMPORARY_PROVIDER_FAILURE', 'STALE_PROGRESS', 'VEHICLE_MISSING', 'IDENTITY_CONFLICT',
            'PROGRESS_REGRESSION', 'TRAVERSAL_AMBIGUOUS', 'CONTINUITY_GAP_EXCEEDED', 'METADATA_CHANGED',
            'TARGET_NOT_AHEAD'))
        OR (status = 'COMPLETED' AND checkpoint_evidence <> 'UNVERIFIED_REFERENCE'
            AND state_reason IS NOT NULL AND state_reason IN ('ARRIVAL_OBSERVED', 'PASSED_OBSERVED'))
        OR (status = 'CANCELLED' AND state_reason IS NOT NULL AND state_reason = 'USER_CANCELLED')
        OR (status = 'EXPIRED' AND state_reason IS NOT NULL AND state_reason = 'SESSION_LIFETIME_EXPIRED')
    ),
    ADD CONSTRAINT ck_boarding_sessions_end CHECK (
        (status IN ('TRACKING', 'SUSPENDED') AND ended_at IS NULL)
        OR (status IN ('COMPLETED', 'CANCELLED', 'EXPIRED') AND ended_at IS NOT NULL)
    );
