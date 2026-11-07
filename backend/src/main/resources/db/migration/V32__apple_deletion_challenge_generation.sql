CREATE TABLE apple_deletion_generation (
    singleton_id TINYINT NOT NULL PRIMARY KEY,
    generation BIGINT NOT NULL,
    CONSTRAINT ck_apple_deletion_generation_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_apple_deletion_generation_nonnegative CHECK (generation >= 0)
);

INSERT INTO apple_deletion_generation (singleton_id, generation) VALUES (1, 0);

ALTER TABLE apple_identity_boundaries
    ADD COLUMN latest_deletion_generation BIGINT NOT NULL DEFAULT 0;
