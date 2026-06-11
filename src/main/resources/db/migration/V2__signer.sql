-- Which signer backend each user was provisioned with. Wallet keys differ
-- per backend, so a deployment-wide SIGNER flip must not silently serve a
-- user with the other backend's (empty) wallet — requests hard-fail on a
-- mismatch instead (see SdkAccess.withUser).
ALTER TABLE users ADD COLUMN signer VARCHAR(16) NOT NULL DEFAULT 'seed';

-- Turnkey wallet backing the user's keys. Set iff signer = 'turnkey'.
ALTER TABLE users ADD COLUMN turnkey_wallet_id VARCHAR(64);
