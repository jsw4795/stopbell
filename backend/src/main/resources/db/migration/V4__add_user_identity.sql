ALTER TABLE users
    ADD COLUMN auth_provider VARCHAR(20) NOT NULL,
    ADD COLUMN provider_user_id VARCHAR(255) NOT NULL,
    ADD CONSTRAINT uk_users_auth_provider_provider_user_id UNIQUE (auth_provider, provider_user_id);
