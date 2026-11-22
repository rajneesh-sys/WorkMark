CREATE INDEX idx_user_accounts_company_employee
    ON user_accounts(company_id, employee_id)
    WHERE employee_id IS NOT NULL;
