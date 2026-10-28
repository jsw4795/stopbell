-- A selected vehicle can be registered before a trusted progress baseline exists.
-- Unverified references retain identity/monotonicity evidence and never authorize notifications.
ALTER TABLE boarding_sessions
    DROP CHECK ck_boarding_sessions_status,
    DROP CHECK ck_boarding_sessions_reason,
    DROP CHECK ck_boarding_sessions_evidence,
    DROP CHECK ck_boarding_sessions_checkpoint,
    DROP CHECK ck_boarding_sessions_end,
    DROP INDEX uk_boarding_sessions_open_user,
    DROP COLUMN open_user_id,
    ADD COLUMN open_user_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('VERIFYING', 'TRACKING', 'SUSPENDED') THEN user_id ELSE NULL END
    ) VIRTUAL,
    ADD CONSTRAINT uk_boarding_sessions_open_user UNIQUE (open_user_id),
    ADD CONSTRAINT ck_boarding_sessions_status CHECK (
        status IN ('VERIFYING', 'TRACKING', 'SUSPENDED', 'COMPLETED', 'CANCELLED', 'EXPIRED')
    ),
    ADD CONSTRAINT ck_boarding_sessions_reason CHECK (
        (status = 'VERIFYING' AND checkpoint_evidence = 'UNVERIFIED_REFERENCE'
            AND state_reason IS NOT NULL AND state_reason IN (
                'BASELINE_UNVERIFIED', 'TEMPORARY_PROVIDER_FAILURE', 'STALE_PROGRESS', 'METADATA_CHANGED'))
        OR (status = 'TRACKING' AND state_reason IS NULL AND checkpoint_evidence <> 'UNVERIFIED_REFERENCE')
        OR (status = 'SUSPENDED' AND state_reason IS NOT NULL AND state_reason IN (
            'TEMPORARY_PROVIDER_FAILURE', 'STALE_PROGRESS', 'VEHICLE_MISSING', 'IDENTITY_CONFLICT',
            'PROGRESS_REGRESSION', 'TRAVERSAL_AMBIGUOUS', 'CONTINUITY_GAP_EXCEEDED', 'METADATA_CHANGED',
            'TARGET_NOT_AHEAD'))
        OR (status = 'COMPLETED' AND checkpoint_evidence <> 'UNVERIFIED_REFERENCE'
            AND state_reason IS NOT NULL AND state_reason IN ('ARRIVAL_OBSERVED', 'PASSED_OBSERVED'))
        OR (status = 'CANCELLED' AND state_reason IS NOT NULL AND state_reason = 'USER_CANCELLED')
        OR (status = 'EXPIRED' AND state_reason IS NOT NULL AND state_reason = 'SESSION_LIFETIME_EXPIRED')
    ),
    ADD CONSTRAINT ck_boarding_sessions_checkpoint CHECK (
        (checkpoint_stop_order > 0 AND checkpoint_index >= 0)
        OR (checkpoint_evidence = 'UNVERIFIED_REFERENCE' AND checkpoint_stop_external_id IS NULL
            AND checkpoint_stop_order = 0 AND checkpoint_index = -1)
    ),
    ADD CONSTRAINT ck_boarding_sessions_evidence CHECK (
        (checkpoint_evidence IN ('ID_ORDER', 'UNVERIFIED_REFERENCE')
            AND checkpoint_stop_external_id IS NOT NULL AND CHAR_LENGTH(TRIM(checkpoint_stop_external_id)) > 0)
        OR (checkpoint_evidence = 'VERIFIED_ORDER_ONLY' AND checkpoint_stop_external_id IS NULL)
        OR (checkpoint_evidence = 'UNVERIFIED_REFERENCE' AND checkpoint_stop_external_id IS NULL
            AND checkpoint_stop_order = 0 AND checkpoint_index = -1)
    ),
    ADD CONSTRAINT ck_boarding_sessions_end CHECK (
        (status IN ('VERIFYING', 'TRACKING', 'SUSPENDED') AND ended_at IS NULL)
        OR (status IN ('COMPLETED', 'CANCELLED', 'EXPIRED') AND ended_at IS NOT NULL)
    );
