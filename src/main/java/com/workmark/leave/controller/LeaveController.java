package com.workmark.leave.controller;

import com.workmark.common.response.ApiResponse;
import com.workmark.leave.dto.LeaveApplyRequest;
import com.workmark.leave.dto.LeaveView;
import com.workmark.leave.service.LeaveService;
import com.workmark.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leaves")
@PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER','EMPLOYEE')")
@Tag(name = "Leave", description = "Leave applications, history, and approval workflow")
public class LeaveController {
    private final LeaveService leaves;
    private final CurrentTenant tenant;
    public LeaveController(LeaveService leaves, CurrentTenant tenant) {
        this.leaves = leaves;
        this.tenant = tenant;
    }
    @PostMapping
    @Operation(summary = "Apply for leave")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LeaveView> apply(@Valid @RequestBody LeaveApplyRequest request) {
        return ApiResponse.ok("Leave request submitted", leaves.apply(tenant.companyId(), tenant.user(), request));
    }
    @GetMapping("/employee/{employeeId}")
    public ApiResponse<List<LeaveView>> employeeRequests(@PathVariable UUID employeeId) {
        return ApiResponse.ok("Leave requests", leaves.employeeRequests(tenant.companyId(), tenant.user(), employeeId));
    }
    @GetMapping("/mine")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ApiResponse<List<LeaveView>> mine() {
        return ApiResponse.ok("Leave history", leaves.employeeRequests(tenant.companyId(), tenant.user(),
                tenant.user().getEmployeeId()));
    }
    @GetMapping("/pending")
    @Operation(summary = "List pending requests visible to the approver")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER')")
    public ApiResponse<List<LeaveView>> pending() {
        return ApiResponse.ok("Pending leave requests", leaves.pending(tenant.companyId(), tenant.user()));
    }
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER')")
    public ApiResponse<LeaveView> approve(@PathVariable UUID id) {
        return ApiResponse.ok("Leave approved", leaves.approve(tenant.companyId(), tenant.user(), id));
    }
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER')")
    public ApiResponse<LeaveView> reject(@PathVariable UUID id) {
        return ApiResponse.ok("Leave rejected", leaves.reject(tenant.companyId(), tenant.user(), id));
    }
    @PostMapping("/{id}/cancel")
    public ApiResponse<LeaveView> cancel(@PathVariable UUID id) {
        return ApiResponse.ok("Leave cancelled", leaves.cancel(tenant.companyId(), tenant.user(), id));
    }
}
