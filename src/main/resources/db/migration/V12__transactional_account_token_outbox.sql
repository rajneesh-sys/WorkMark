CREATE TABLE account_token_outbox (
    id UUID PRIMARY KEY,
    payload_nonce BYTEA,
    payload_ciphertext BYTEA,
    status VARCHAR(16) NOT NULL CHECK (
        status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'EXHAUSTED', 'EXPIRED')
    ),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    lease_token UUID,
    last_failure_category VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_account_token_outbox_payload CHECK (
        (status IN ('PENDING', 'PROCESSING') AND payload_nonce IS NOT NULL AND payload_ciphertext IS NOT NULL)
        OR (status IN ('DELIVERED', 'EXHAUSTED', 'EXPIRED')
            AND payload_nonce IS NULL AND payload_ciphertext IS NULL)
    ),
    CONSTRAINT ck_account_token_outbox_lease CHECK (
        (status = 'PROCESSING' AND lease_until IS NOT NULL AND lease_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_until IS NULL AND lease_token IS NULL)
    )
);

CREATE INDEX idx_account_token_outbox_pending
    ON account_token_outbox(next_attempt_at, created_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_account_token_outbox_expired_lease
    ON account_token_outbox(lease_until)
    WHERE status = 'PROCESSING';
CREATE INDEX idx_account_token_outbox_retention
    ON account_token_outbox(updated_at)
    WHERE status IN ('DELIVERED', 'EXHAUSTED', 'EXPIRED');
