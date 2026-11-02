CREATE TABLE apple_credentials (
    user_id BIGINT NOT NULL,
    encrypted_refresh_token VARCHAR(12000) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_apple_credentials_user FOREIGN KEY (user_id) REFERENCES users(id)
);
