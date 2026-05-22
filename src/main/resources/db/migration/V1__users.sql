CREATE TABLE users (
    user_id        VARCHAR(64)  PRIMARY KEY,
    api_key_hash   CHAR(64)     NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    webhook_id     VARCHAR(64)
);

CREATE INDEX idx_users_created_at ON users (created_at);
