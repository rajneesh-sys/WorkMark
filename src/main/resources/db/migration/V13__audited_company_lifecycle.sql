CREATE TABLE company_status_history (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    actor_id UUID NOT NULL REFERENCES user_accounts(id),
    previous_status VARCHAR(20) NOT NULL CHECK (previous_status IN ('ACTIVE', 'INACTIVE')),
    new_status VARCHAR(20) NOT NULL CHECK (new_status IN ('ACTIVE', 'INACTIVE')),
    changed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_company_status_history_transition CHECK (previous_status <> new_status)
);

CREATE INDEX idx_company_status_history_company_time
    ON company_status_history(company_id, changed_at DESC);

CREATE FUNCTION prevent_company_status_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'company status history is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_company_status_history_immutable
    BEFORE UPDATE OR DELETE ON company_status_history
    FOR EACH ROW EXECUTE FUNCTION prevent_company_status_history_mutation();
