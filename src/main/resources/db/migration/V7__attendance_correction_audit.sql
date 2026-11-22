ALTER TABLE attendance
    ADD CONSTRAINT uq_attendance_id_company UNIQUE (id, company_id);

ALTER TABLE user_accounts
    ADD CONSTRAINT uq_user_accounts_id_company UNIQUE (id, company_id);

CREATE UNIQUE INDEX uq_attendance_one_open_per_employee
    ON attendance(company_id, employee_id)
    WHERE check_out_time IS NULL;

CREATE TABLE attendance_corrections (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    attendance_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    corrected_by UUID NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    corrected_at TIMESTAMPTZ NOT NULL,
    original_attendance_date DATE NOT NULL,
    original_check_in_time TIMESTAMPTZ NOT NULL,
    original_check_out_time TIMESTAMPTZ,
    original_working_minutes INTEGER,
    original_status VARCHAR(20) NOT NULL CHECK (
        original_status IN ('PRESENT', 'ABSENT', 'HALF_DAY', 'LATE', 'ON_LEAVE')
    ),
    corrected_attendance_date DATE NOT NULL,
    corrected_check_in_time TIMESTAMPTZ NOT NULL,
    corrected_check_out_time TIMESTAMPTZ,
    corrected_working_minutes INTEGER,
    corrected_status VARCHAR(20) NOT NULL CHECK (
        corrected_status IN ('PRESENT', 'ABSENT', 'HALF_DAY', 'LATE', 'ON_LEAVE')
    ),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_attendance_correction_record FOREIGN KEY (attendance_id, company_id)
        REFERENCES attendance(id, company_id),
    CONSTRAINT fk_attendance_correction_employee FOREIGN KEY (employee_id, company_id)
        REFERENCES employees(id, company_id),
    CONSTRAINT fk_attendance_correction_actor FOREIGN KEY (corrected_by, company_id)
        REFERENCES user_accounts(id, company_id),
    CONSTRAINT ck_attendance_correction_original_checkout CHECK (
        original_check_out_time IS NULL OR original_check_out_time >= original_check_in_time
    ),
    CONSTRAINT ck_attendance_correction_new_checkout CHECK (
        corrected_check_out_time IS NULL OR corrected_check_out_time >= corrected_check_in_time
    ),
    CONSTRAINT ck_attendance_correction_minutes CHECK (
        (original_working_minutes IS NULL OR original_working_minutes >= 0)
        AND (corrected_working_minutes IS NULL OR corrected_working_minutes >= 0)
    )
);

CREATE INDEX idx_attendance_corrections_company_record_time
    ON attendance_corrections(company_id, attendance_id, corrected_at DESC);

CREATE FUNCTION prevent_attendance_correction_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'attendance correction history is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_attendance_corrections_immutable
    BEFORE UPDATE OR DELETE ON attendance_corrections
    FOR EACH ROW EXECUTE FUNCTION prevent_attendance_correction_mutation();
