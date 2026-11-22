CREATE INDEX idx_employees_company_manager_name
    ON employees(company_id, manager_id, last_name, first_name);

CREATE INDEX idx_departments_company_name
    ON departments(company_id, department_name);

CREATE INDEX idx_leave_company_employee_created
    ON leave_requests(company_id, employee_id, created_at DESC);

CREATE INDEX idx_leave_company_status_created
    ON leave_requests(company_id, status, created_at);
