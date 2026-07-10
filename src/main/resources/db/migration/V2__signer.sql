-- Which signer backend each user was provisioned with. Wallet keys differ
-- per backend, so a deployment-wide SIGNER flip must not silently serve a
-- user with the other backend's (empty) wallet — requests hard-fail on a
-- mismatch instead (see SdkAccess.withUser).
ALTER TABLE users ADD COLUMN signer VARCHAR(16) NOT NULL DEFAULT 'seed';

-- Turnkey wallet backing the user's keys. Set iff signer = 'turnkey'.
ALTER TABLE users ADD COLUMN turnkey_wallet_id VARCHAR(64);

-- Client-approved sends run Turnkey under delegated access: a per-user
-- sub-organization whose sole root user is the end user's passkey, plus a
-- backend-controlled delegated API key that is a policy-scoped member allowed
-- to run receive/auth/FROST activities but NOT SPARK_PREPARE_TRANSFER — so the
-- server can receive autonomously yet cannot move funds out. See Turnkey.kt.

-- The user's Turnkey sub-organization id. The wallet, the passkey root user and
-- the delegated user all live inside it. Set iff signer = 'turnkey'.
ALTER TABLE users ADD COLUMN turnkey_sub_org_id VARCHAR(64);

-- The wallet's Spark-format account address — the `signWith` the client passes
-- to Turnkey's SPARK_PREPARE_TRANSFER activity. Set iff signer = 'turnkey'.
ALTER TABLE users ADD COLUMN turnkey_spark_address VARCHAR(128);
