package com.workmark.employee.service;

import com.workmark.auth.entity.UserAccount;
import com.workmark.auth.repository.UserAccountRepository;
import com.workmark.common.exception.ApiException;
import com.workmark.department.repository.DepartmentRepository;
import com.workmark.employee.dto.EmployeeRequest;
import com.workmark.employee.dto.EmployeeView;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.entity.EmployeeStatus;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Set;

@Service
public class EmployeeService {
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "employeeCode", "firstName", "lastName", "email", "designation", "joiningDate", "status", "id");
    private final EmployeeRepository employees;
    private final DepartmentRepository departments;
    private final UserAccountRepository users;
    private final EmployeeAccessPolicy accessPolicy;

    public EmployeeService(EmployeeRepository employees, DepartmentRepository departments,
                           UserAccountRepository users, EmployeeAccessPolicy accessPolicy) {
        this.employees = employees;
        this.departments = departments;
        this.users = users;
        this.accessPolicy = accessPolicy;
    }

    @Transactional
    public EmployeeView create(UUID companyId, CurrentUser user, EmployeeRequest request) {
        accessPolicy.requireCompanyStaff(user, companyId);
        validateReferences(companyId, request, null);
        if (employees.existsByCompanyIdAndEmployeeCodeIgnoreCase(companyId, request.employeeCode())
                || employees.existsByCompanyIdAndEmailIgnoreCase(companyId, request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "Employee code or email is already in use");
        }
        return view(employees.save(new Employee(companyId, request.employeeCode(), request.firstName(),
                request.lastName(), request.email(), request.phone(), request.departmentId(),
                request.managerId(), request.designation(), request.joiningDate())));
    }

    @Transactional
    public EmployeeView update(UUID companyId, CurrentUser user, UUID id, EmployeeRequest request) {
        accessPolicy.requireCompanyStaff(user, companyId);
        Employee employee = find(companyId, id);
        validateReferences(companyId, request, id);
        if (employees.existsByCompanyIdAndEmployeeCodeIgnoreCaseAndIdNot(companyId, request.employeeCode(), id)
                || employees.existsByCompanyIdAndEmailIgnoreCaseAndIdNot(companyId, request.email(), id)) {
            throw new ApiException(HttpStatus.CONFLICT, "Employee code or email is already in use");
        }
        employee.update(request.employeeCode(), request.firstName(), request.lastName(), request.email(),
                request.phone(), request.departmentId(), request.managerId(), request.designation(),
                request.joiningDate());
        return view(employee);
    }

    @Transactional(readOnly = true)
    public EmployeeView get(UUID companyId, CurrentUser user, UUID id) {
        return view(accessPolicy.findAccessible(companyId, user, id));
    }

    @Transactional(readOnly = true)
    public Page<EmployeeView> list(UUID companyId, CurrentUser user, String query, Pageable pageable) {
        accessPolicy.requireTenant(companyId, user);
        if (pageable.getPageNumber() < 0 || pageable.getPageSize() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Page must be non-negative and size must be positive");
        }
        validateSort(pageable.getSort());
        Sort sort = pageable.getSort();
        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by("id").ascending());
        }
        if (!sort.isSorted()) {
            sort = Sort.by("lastName").ascending().and(Sort.by("id").ascending());
        }
        Pageable bounded = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100), sort);
        String normalizedQuery = query == null ? "" : query.trim();
        Page<Employee> page;
        if (user.getRole() == com.workmark.auth.entity.UserRole.MANAGER) {
            if (user.getEmployeeId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Manager account is not linked to an employee");
            }
            page = normalizedQuery.isEmpty()
                    ? employees.findAllByCompanyIdAndManagerId(companyId, user.getEmployeeId(), bounded)
                    : employees.searchDirectReports(companyId, user.getEmployeeId(), normalizedQuery, bounded);
        } else if (user.getRole() == com.workmark.auth.entity.UserRole.EMPLOYEE) {
            if (user.getEmployeeId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Employee account is not linked to an employee");
            }
            page = normalizedQuery.isEmpty()
                    ? employees.findAllByCompanyIdAndId(companyId, user.getEmployeeId(), bounded)
                    : employees.searchById(companyId, user.getEmployeeId(), normalizedQuery, bounded);
        } else if (user.getRole() == com.workmark.auth.entity.UserRole.COMPANY_ADMIN
                || user.getRole() == com.workmark.auth.entity.UserRole.HR) {
            page = normalizedQuery.isEmpty()
                    ? employees.findAllByCompanyId(companyId, bounded)
                    : employees.search(companyId, normalizedQuery, bounded);
        } else {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to list employees");
        }
        return page.map(this::view);
    }

    @Transactional
    public EmployeeView setStatus(UUID companyId, CurrentUser user, UUID id, EmployeeStatus status) {
        accessPolicy.requireCompanyStaff(user, companyId);
        Employee employee = employees.findForUpdateByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
        employee.setStatus(status);
        if (status == EmployeeStatus.INACTIVE) {
            for (UserAccount account : users.findAllForUpdateByCompanyIdAndEmployeeId(companyId, id)) {
                account.deactivate();
            }
        }
        return view(employee);
    }

    private void validateSort(Sort sort) {
        for (Sort.Order order : sort) {
            if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid employee sort field");
            }
        }
    }

    private void validateReferences(UUID companyId, EmployeeRequest request, UUID employeeId) {
        if (request.departmentId() != null
                && !departments.existsByIdAndCompanyId(request.departmentId(), companyId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Department does not belong to this company");
        }
        if (request.managerId() != null) {
            if (request.managerId().equals(employeeId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "An employee cannot be their own manager");
            }
            if (!employees.existsByIdAndCompanyId(request.managerId(), companyId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Manager does not belong to this company");
            }
        }
    }

    private Employee find(UUID companyId, UUID id) {
        return employees.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
    }
    private EmployeeView view(Employee e) {
        return new EmployeeView(e.getId(), e.getCompanyId(), e.getEmployeeCode(), e.getFirstName(),
                e.getLastName(), e.getEmail(), e.getPhone(), e.getDepartmentId(), e.getManagerId(),
                e.getDesignation(), e.getJoiningDate(), e.getStatus());
    }
}
