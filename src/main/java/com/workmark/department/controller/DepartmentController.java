package com.workmark.department.controller;

import com.workmark.common.response.ApiResponse;
import com.workmark.department.dto.DepartmentRequest;
import com.workmark.department.dto.DepartmentView;
import com.workmark.department.service.DepartmentService;
import com.workmark.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/departments")
@PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER')")
@Tag(name = "Departments", description = "Company department management")
public class DepartmentController {
    private final DepartmentService departments;
    private final CurrentTenant tenant;

    public DepartmentController(DepartmentService departments, CurrentTenant tenant) {
        this.departments = departments;
        this.tenant = tenant;
    }
    @PostMapping
    @Operation(summary = "Create a department")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR')")
    public ApiResponse<DepartmentView> create(@Valid @RequestBody DepartmentRequest request) {
        return ApiResponse.ok("Department created", departments.create(tenant.companyId(), request));
    }
    @PutMapping("/{id}")
    @Operation(summary = "Update a department")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR')")
    public ApiResponse<DepartmentView> update(@PathVariable UUID id, @Valid @RequestBody DepartmentRequest request) {
        return ApiResponse.ok("Department updated", departments.update(tenant.companyId(), id, request));
    }
    @GetMapping
    @Operation(summary = "List company departments")
    public ApiResponse<List<DepartmentView>> list() {
        return ApiResponse.ok("Departments", departments.list(tenant.companyId()));
    }
    @GetMapping("/{id}")
    @Operation(summary = "Get department details")
    public ApiResponse<DepartmentView> get(@PathVariable UUID id) {
        return ApiResponse.ok("Department details", departments.get(tenant.companyId(), id));
    }
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a department without employees")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        departments.delete(tenant.companyId(), id);
        return ApiResponse.ok("Department deleted", null);
    }
}
