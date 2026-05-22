CREATE TABLE users (
    user_id        VARCHAR(64)  PRIMARY KEY,
    api_key_hash   CHAR(64)     NOT NULL UNIQUE,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    webhook_id     VARCHAR(64),
    INDEX idx_users_created_at (created_at)
);
