ALTER TABLE user_accounts
    ADD COLUMN password_reset_token_hash VARCHAR(64),
    ADD COLUMN password_reset_expires_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_password_reset_token_pair CHECK (
        (password_reset_token_hash IS NULL) = (password_reset_expires_at IS NULL)
    );

CREATE UNIQUE INDEX uq_user_accounts_password_reset_token
    ON user_accounts(password_reset_token_hash)
    WHERE password_reset_token_hash IS NOT NULL;
