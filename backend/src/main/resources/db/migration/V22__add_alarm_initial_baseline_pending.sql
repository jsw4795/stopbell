ALTER TABLE alarms
    ADD COLUMN initial_baseline_pending BOOLEAN NOT NULL DEFAULT FALSE AFTER activation_generation;
