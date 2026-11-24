ALTER TABLE user_accounts
    ADD COLUMN password_reset_requested_at TIMESTAMPTZ;

UPDATE user_accounts
SET password_reset_requested_at = COALESCE(password_reset_expires_at - INTERVAL '30 minutes', updated_at)
WHERE password_reset_token_hash IS NOT NULL;

ALTER TABLE user_accounts
    ADD CONSTRAINT ck_password_reset_requested_pair CHECK (
        (password_reset_token_hash IS NULL) = (password_reset_requested_at IS NULL)
    );

CREATE TABLE password_reset_attempt_buckets (
    key_hash CHAR(64) PRIMARY KEY,
    failure_count INTEGER NOT NULL CHECK (failure_count >= 0),
    window_started_at TIMESTAMPTZ NOT NULL,
    blocked_until TIMESTAMPTZ,
    block_level INTEGER NOT NULL DEFAULT 0 CHECK (block_level >= 0),
    last_requested_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_password_reset_attempt_buckets_blocked
    ON password_reset_attempt_buckets(blocked_until)
    WHERE blocked_until IS NOT NULL;
