ALTER TABLE user_accounts
    ADD COLUMN display_name VARCHAR(160),
    ADD COLUMN activation_token_hash VARCHAR(64),
    ADD COLUMN activation_expires_at TIMESTAMPTZ;

ALTER TABLE user_accounts
    ALTER COLUMN password_hash DROP NOT NULL,
    ADD CONSTRAINT ck_user_active_password CHECK (NOT active OR password_hash IS NOT NULL),
    ADD CONSTRAINT ck_user_role_company CHECK (
        (role = 'SUPER_ADMIN' AND company_id IS NULL)
        OR (role <> 'SUPER_ADMIN' AND company_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_activation_token_pair CHECK (
        (activation_token_hash IS NULL) = (activation_expires_at IS NULL)
    );

CREATE UNIQUE INDEX uq_user_accounts_single_super_admin
    ON user_accounts(role) WHERE role = 'SUPER_ADMIN';
CREATE UNIQUE INDEX uq_user_accounts_activation_token
    ON user_accounts(activation_token_hash) WHERE activation_token_hash IS NOT NULL;
