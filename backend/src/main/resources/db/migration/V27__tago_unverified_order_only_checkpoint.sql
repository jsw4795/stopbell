ALTER TABLE boarding_sessions
    DROP CHECK ck_boarding_sessions_evidence,
    ADD CONSTRAINT ck_boarding_sessions_evidence CHECK (
        (checkpoint_evidence IN ('ID_ORDER', 'UNVERIFIED_REFERENCE')
            AND checkpoint_stop_external_id IS NOT NULL AND CHAR_LENGTH(TRIM(checkpoint_stop_external_id)) > 0)
        OR (checkpoint_evidence = 'VERIFIED_ORDER_ONLY' AND checkpoint_stop_external_id IS NULL)
        OR (checkpoint_evidence = 'UNVERIFIED_REFERENCE' AND checkpoint_stop_external_id IS NULL
            AND checkpoint_stop_order = 0 AND checkpoint_index = -1)
        OR (provider = 'TAGO' AND checkpoint_evidence = 'UNVERIFIED_REFERENCE'
            AND checkpoint_stop_external_id IS NULL AND checkpoint_stop_order > 0 AND checkpoint_index >= 0)
    );
