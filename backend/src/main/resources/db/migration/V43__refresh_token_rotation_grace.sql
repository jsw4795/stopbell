-- A rotated Refresh Token row is kept briefly so a client that lost the rotation response can retry once.
ALTER TABLE refresh_tokens
    ADD COLUMN rotated_at DATETIME(6) NULL;
