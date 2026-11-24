CREATE INDEX idx_password_reset_attempt_buckets_cleanup
    ON password_reset_attempt_buckets(last_requested_at);
