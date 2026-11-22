CREATE TABLE companies (
    id UUID PRIMARY KEY,
    company_name VARCHAR(160) NOT NULL,
    email VARCHAR(254) NOT NULL,
    phone VARCHAR(40),
    address VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_companies_email_ci ON companies (lower(email));

CREATE TABLE departments (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    department_name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_department_id_company UNIQUE (id, company_id)
);
CREATE INDEX idx_departments_company ON departments(company_id);
CREATE UNIQUE INDEX uq_department_company_name_ci ON departments(company_id, lower(department_name));

CREATE TABLE employees (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    employee_code VARCHAR(60) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    phone VARCHAR(40),
    department_id UUID,
    manager_id UUID,
    designation VARCHAR(120),
    joining_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_employee_department FOREIGN KEY (department_id, company_id)
        REFERENCES departments(id, company_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_employee_manager FOREIGN KEY (manager_id, company_id)
        REFERENCES employees(id, company_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_employee_id_company UNIQUE (id, company_id)
);
CREATE UNIQUE INDEX uq_employee_company_code_ci ON employees(company_id, lower(employee_code));
CREATE UNIQUE INDEX uq_employee_company_email_ci ON employees(company_id, lower(email));
CREATE INDEX idx_employees_company ON employees(company_id);
CREATE INDEX idx_employees_company_department ON employees(company_id, department_id);
CREATE INDEX idx_employees_company_name ON employees(company_id, last_name, first_name);

CREATE TABLE user_accounts (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    employee_id UUID,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(30) NOT NULL CHECK (role IN ('SUPER_ADMIN', 'COMPANY_ADMIN', 'HR', 'MANAGER', 'EMPLOYEE')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    token_version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_user_employee FOREIGN KEY (employee_id, company_id)
        REFERENCES employees(id, company_id)
);
CREATE UNIQUE INDEX uq_user_accounts_email_ci ON user_accounts(lower(email));
CREATE INDEX idx_user_accounts_company ON user_accounts(company_id);

CREATE TABLE leave_requests (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    employee_id UUID NOT NULL,
    leave_type VARCHAR(20) NOT NULL CHECK (leave_type IN ('CASUAL', 'SICK', 'EARNED', 'UNPAID')),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    number_of_days NUMERIC(6,2) NOT NULL CHECK (number_of_days > 0),
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    approved_by UUID REFERENCES user_accounts(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_leave_date_range CHECK (end_date >= start_date),
    CONSTRAINT fk_leave_employee FOREIGN KEY (employee_id, company_id)
        REFERENCES employees(id, company_id)
);
CREATE INDEX idx_leave_company_employee ON leave_requests(company_id, employee_id, start_date);
CREATE INDEX idx_leave_company_status ON leave_requests(company_id, status);

CREATE TABLE attendance (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    employee_id UUID NOT NULL,
    attendance_date DATE NOT NULL,
    check_in_time TIMESTAMPTZ NOT NULL,
    check_out_time TIMESTAMPTZ,
    total_working_minutes INTEGER,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PRESENT', 'ABSENT', 'HALF_DAY', 'LATE', 'ON_LEAVE')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_attendance_company_employee_date UNIQUE (company_id, employee_id, attendance_date),
    CONSTRAINT fk_attendance_employee FOREIGN KEY (employee_id, company_id)
        REFERENCES employees(id, company_id),
    CONSTRAINT ck_attendance_minutes CHECK (total_working_minutes IS NULL OR total_working_minutes >= 0),
    CONSTRAINT ck_attendance_checkout CHECK (check_out_time IS NULL OR check_out_time >= check_in_time)
);
CREATE INDEX idx_attendance_company_date ON attendance(company_id, attendance_date);
CREATE INDEX idx_attendance_employee_date ON attendance(company_id, employee_id, attendance_date);
