ALTER TABLE alarms
    ADD COLUMN activation_generation BIGINT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT ck_alarms_activation_generation CHECK (activation_generation >= 0);
