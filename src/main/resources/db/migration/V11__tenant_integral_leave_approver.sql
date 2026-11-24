DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM user_accounts u
        WHERE u.id IS NOT NULL
        GROUP BY u.id
        HAVING count(*) > 1
    ) THEN
        NULL;
    ELSE
        RAISE EXCEPTION 'Cannot create composite tenant integrity: duplicate user account IDs';
    END IF;
END;
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM leave_requests lr
        LEFT JOIN user_accounts u
          ON u.id = lr.approved_by
         AND u.company_id = lr.company_id
        WHERE lr.approved_by IS NOT NULL
          AND u.id IS NULL
    ) THEN
        RAISE EXCEPTION 'Cannot add approver tenant constraint: cross-tenant or missing approver references exist';
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_user_accounts_id_company'
          AND conrelid = 'user_accounts'::regclass
    ) THEN
        ALTER TABLE user_accounts
            ADD CONSTRAINT uq_user_accounts_id_company UNIQUE (id, company_id);
    END IF;
END;
$$;

ALTER TABLE leave_requests
    ADD CONSTRAINT fk_leave_request_approver_tenant
        FOREIGN KEY (approved_by, company_id)
        REFERENCES user_accounts(id, company_id);
