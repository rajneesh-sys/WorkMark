DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gist') THEN
        RAISE EXCEPTION 'The btree_gist PostgreSQL extension must be installed before leave migrations';
    END IF;
END;
$$;

ALTER TABLE leave_requests
    ADD CONSTRAINT uq_leave_request_id_company UNIQUE (id, company_id);

ALTER TABLE leave_requests
    ADD CONSTRAINT ex_leave_requests_no_active_overlap
    EXCLUDE USING gist (
        company_id WITH =,
        employee_id WITH =,
        daterange(start_date, end_date, '[]') WITH &&
    )
    WHERE (status IN ('PENDING', 'APPROVED'));

CREATE TABLE leave_policies (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    leave_type VARCHAR(20) NOT NULL CHECK (leave_type IN ('CASUAL', 'SICK', 'EARNED', 'UNPAID')),
    annual_allocation_days NUMERIC(7,2) NOT NULL CHECK (annual_allocation_days >= 0),
    carry_forward_limit_days NUMERIC(7,2) NOT NULL CHECK (carry_forward_limit_days >= 0),
    carry_forward_expiry_months INTEGER NOT NULL CHECK (carry_forward_expiry_months BETWEEN 0 AND 120),
    allow_negative_balance BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_leave_policy_company_type UNIQUE (company_id, leave_type)
);

CREATE TABLE leave_balance_entries (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    leave_type VARCHAR(20) NOT NULL CHECK (leave_type IN ('CASUAL', 'SICK', 'EARNED', 'UNPAID')),
    transaction_type VARCHAR(24) NOT NULL CHECK (
        transaction_type IN (
            'ALLOCATION', 'ACCRUAL', 'CONSUMPTION', 'RESTORATION',
            'MANUAL_ADJUSTMENT', 'CARRY_FORWARD', 'EXPIRY'
        )
    ),
    delta_days NUMERIC(8,2) NOT NULL CHECK (delta_days <> 0),
    leave_request_id UUID,
    actor_id UUID,
    reason VARCHAR(1000) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_leave_balance_employee FOREIGN KEY (employee_id, company_id)
        REFERENCES employees(id, company_id),
    CONSTRAINT fk_leave_balance_actor FOREIGN KEY (actor_id, company_id)
        REFERENCES user_accounts(id, company_id),
    CONSTRAINT fk_leave_balance_request FOREIGN KEY (leave_request_id, company_id)
        REFERENCES leave_requests(id, company_id),
    CONSTRAINT ck_leave_balance_entry_sign CHECK (
        (transaction_type IN ('ALLOCATION', 'ACCRUAL', 'RESTORATION', 'CARRY_FORWARD') AND delta_days > 0)
        OR (transaction_type IN ('CONSUMPTION', 'EXPIRY') AND delta_days < 0)
        OR (transaction_type = 'MANUAL_ADJUSTMENT' AND delta_days <> 0)
    ),
    CONSTRAINT ck_leave_balance_expiration CHECK (expires_at IS NULL OR expires_at > effective_at)
);

CREATE INDEX idx_leave_balance_employee_type_effective
    ON leave_balance_entries(company_id, employee_id, leave_type, effective_at DESC);
CREATE UNIQUE INDEX uq_leave_balance_request_transaction
    ON leave_balance_entries(leave_request_id, transaction_type)
    WHERE leave_request_id IS NOT NULL;

CREATE TABLE leave_status_history (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    leave_request_id UUID NOT NULL,
    previous_status VARCHAR(20) CHECK (
        previous_status IS NULL OR previous_status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')
    ),
    new_status VARCHAR(20) NOT NULL CHECK (
        new_status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')
    ),
    actor_id UUID NOT NULL,
    action VARCHAR(20) NOT NULL CHECK (action IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    comment VARCHAR(1000),
    action_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_leave_history_request FOREIGN KEY (leave_request_id, company_id)
        REFERENCES leave_requests(id, company_id),
    CONSTRAINT fk_leave_history_actor FOREIGN KEY (actor_id, company_id)
        REFERENCES user_accounts(id, company_id)
);

CREATE INDEX idx_leave_status_history_company_request_time
    ON leave_status_history(company_id, leave_request_id, action_at DESC);

CREATE FUNCTION prevent_leave_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'leave audit and ledger history is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_leave_balance_entries_immutable
    BEFORE UPDATE OR DELETE ON leave_balance_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_leave_audit_mutation();

CREATE TRIGGER trg_leave_status_history_immutable
    BEFORE UPDATE OR DELETE ON leave_status_history
    FOR EACH ROW EXECUTE FUNCTION prevent_leave_audit_mutation();
