package com.workmark.employee.controller;

import com.workmark.common.response.ApiResponse;
import com.workmark.common.response.PageResponse;
import com.workmark.employee.dto.EmployeeRequest;
import com.workmark.employee.dto.EmployeeView;
import com.workmark.employee.entity.EmployeeStatus;
import com.workmark.employee.service.EmployeeService;
import com.workmark.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/employees")
@PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER','EMPLOYEE')")
@Tag(name = "Employees", description = "Tenant-scoped employee directory and lifecycle operations")
public class EmployeeController {
    private final EmployeeService employees;
    private final CurrentTenant tenant;

    public EmployeeController(EmployeeService employees, CurrentTenant tenant) {
        this.employees = employees;
        this.tenant = tenant;
    }

    @PostMapping
    @Operation(summary = "Create an employee in the authenticated company")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR')")
    public ApiResponse<EmployeeView> create(@Valid @RequestBody EmployeeRequest request) {
        return ApiResponse.ok("Employee created", employees.create(tenant.companyId(), tenant.user(), request));
    }
    @PutMapping("/{id}")
    @Operation(summary = "Update an employee")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR')")
    public ApiResponse<EmployeeView> update(@PathVariable UUID id, @Valid @RequestBody EmployeeRequest request) {
        return ApiResponse.ok("Employee updated", employees.update(tenant.companyId(), tenant.user(), id, request));
    }
    @GetMapping
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER')")
    @Operation(summary = "Search and page through employees")
    public ApiResponse<PageResponse<EmployeeView>> list(@RequestParam(required = false) String search,
            @ParameterObject @PageableDefault(size = 20, sort = "lastName") Pageable pageable) {
        Page<EmployeeView> result = employees.list(tenant.companyId(), tenant.user(), search, pageable);
        return ApiResponse.ok("Employees", PageResponse.from(result));
    }
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER')")
    @Operation(summary = "Get an employee by ID")
    public ApiResponse<EmployeeView> get(@PathVariable UUID id) {
        return ApiResponse.ok("Employee details", employees.get(tenant.companyId(), tenant.user(), id));
    }
    @GetMapping("/me")
    @PreAuthorize("hasRole('EMPLOYEE')")
    @Operation(summary = "Get the employee record linked to the current account")
    public ApiResponse<EmployeeView> mine() {
        UUID employeeId = tenant.user().getEmployeeId();
        if (employeeId == null) {
            throw new com.workmark.common.exception.ApiException(
                    HttpStatus.FORBIDDEN, "Employee account is not linked to an employee record");
        }
        return ApiResponse.ok("Employee details", employees.get(tenant.companyId(), tenant.user(), employeeId));
    }
    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate an employee")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR')")
    public ApiResponse<EmployeeView> setStatus(@PathVariable UUID id, @RequestParam EmployeeStatus status) {
        return ApiResponse.ok("Employee status updated",
                employees.setStatus(tenant.companyId(), tenant.user(), id, status));
    }
}
