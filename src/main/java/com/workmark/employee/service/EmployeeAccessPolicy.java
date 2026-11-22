package com.workmark.employee.service;

import com.workmark.auth.entity.UserRole;
import com.workmark.common.exception.ApiException;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class EmployeeAccessPolicy {
    private final EmployeeRepository employees;

    public EmployeeAccessPolicy(EmployeeRepository employees) {
        this.employees = employees;
    }

    public void requireTenant(UUID companyId, CurrentUser user) {
        if (companyId == null || user.getCompanyId() == null || !companyId.equals(user.getCompanyId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This operation is not available for this company");
        }
    }

    public Employee findAccessible(UUID companyId, CurrentUser user, UUID employeeId) {
        requireTenant(companyId, user);
        if (employeeId == null) {
            throw employeeNotFound();
        }
        if (user.getRole() == UserRole.EMPLOYEE) {
            if (!employeeId.equals(user.getEmployeeId())) {
                throw employeeNotFound();
            }
            return employees.findByIdAndCompanyId(employeeId, companyId).orElseThrow(this::employeeNotFound);
        }
        if (user.getRole() == UserRole.MANAGER) {
            if (user.getEmployeeId() == null) {
                throw employeeNotFound();
            }
            return employees.findByIdAndCompanyIdAndManagerId(employeeId, companyId, user.getEmployeeId())
                    .orElseThrow(this::employeeNotFound);
        }
        if (user.getRole() == UserRole.COMPANY_ADMIN || user.getRole() == UserRole.HR) {
            return employees.findByIdAndCompanyId(employeeId, companyId).orElseThrow(this::employeeNotFound);
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to access employee records");
    }

    public Employee findAccessibleForUpdate(UUID companyId, CurrentUser user, UUID employeeId) {
        requireTenant(companyId, user);
        if (employeeId == null) {
            throw employeeNotFound();
        }
        if (user.getRole() == UserRole.MANAGER) {
            if (user.getEmployeeId() == null) {
                throw employeeNotFound();
            }
            return employees.findForUpdateByIdAndCompanyIdAndManagerId(
                            employeeId, companyId, user.getEmployeeId())
                    .orElseThrow(this::employeeNotFound);
        }
        findAccessible(companyId, user, employeeId);
        return employees.findForUpdateByIdAndCompanyId(employeeId, companyId)
                .orElseThrow(this::employeeNotFound);
    }

    public void requireCompanyStaff(CurrentUser user, UUID companyId) {
        requireTenant(companyId, user);
        if (user.getRole() != UserRole.COMPANY_ADMIN && user.getRole() != UserRole.HR) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to administer employees");
        }
    }

    public void requireApprover(UUID companyId, CurrentUser user) {
        requireTenant(companyId, user);
        if (user.getRole() != UserRole.COMPANY_ADMIN
                && user.getRole() != UserRole.HR
                && user.getRole() != UserRole.MANAGER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to approve requests");
        }
    }

    private ApiException employeeNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "Employee not found");
    }
}
