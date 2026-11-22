CREATE TABLE login_attempt_buckets (
    key_hash CHAR(64) PRIMARY KEY,
    failure_count INTEGER NOT NULL CHECK (failure_count >= 0),
    window_started_at TIMESTAMPTZ NOT NULL,
    blocked_until TIMESTAMPTZ,
    block_level INTEGER NOT NULL DEFAULT 0 CHECK (block_level >= 0),
    last_touched TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_login_attempt_buckets_last_touched
    ON login_attempt_buckets(last_touched);
