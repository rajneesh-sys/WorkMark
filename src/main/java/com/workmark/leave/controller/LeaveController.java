package com.workmark.leave.controller;

import com.workmark.common.response.ApiResponse;
import com.workmark.common.response.PageResponse;
import com.workmark.leave.dto.LeaveActionRequest;
import com.workmark.leave.dto.LeaveApplyRequest;
import com.workmark.leave.dto.LeaveBalanceEntryView;
import com.workmark.leave.dto.LeaveBalanceTransactionRequest;
import com.workmark.leave.dto.LeaveBalanceView;
import com.workmark.leave.dto.LeavePolicyRequest;
import com.workmark.leave.dto.LeavePolicyView;
import com.workmark.leave.dto.LeaveStatusHistoryView;
import com.workmark.leave.dto.LeaveView;
import com.workmark.leave.entity.LeaveType;
import com.workmark.leave.service.LeaveLedgerService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springdoc.core.annotations.ParameterObject;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leaves")
@PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER','EMPLOYEE')")
@Tag(name = "Leave", description = "Leave applications, history, and approval workflow")
public class LeaveController {
    private final LeaveService leaves;
    private final LeaveLedgerService ledger;
    private final CurrentTenant tenant;
    public LeaveController(LeaveService leaves, LeaveLedgerService ledger, CurrentTenant tenant) {
        this.leaves = leaves;
        this.ledger = ledger;
        this.tenant = tenant;
    }
    @PostMapping
    @Operation(summary = "Apply for leave")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LeaveView> apply(@Valid @RequestBody LeaveApplyRequest request) {
        return ApiResponse.ok("Leave request submitted", leaves.apply(tenant.companyId(), tenant.user(), request));
    }
    @GetMapping("/employee/{employeeId}")
    public ApiResponse<PageResponse<LeaveView>> employeeRequests(@PathVariable UUID employeeId,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("Leave requests",
                PageResponse.from(leaves.employeeRequests(tenant.companyId(), tenant.user(), employeeId, pageable)));
    }
    @GetMapping("/mine")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ApiResponse<PageResponse<LeaveView>> mine(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("Leave history", PageResponse.from(leaves.employeeRequests(
                tenant.companyId(), tenant.user(), tenant.user().getEmployeeId(), pageable)));
    }
    @GetMapping("/pending")
    @Operation(summary = "List pending requests visible to the approver")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER')")
    public ApiResponse<PageResponse<LeaveView>> pending(
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("Pending leave requests",
                PageResponse.from(leaves.pending(tenant.companyId(), tenant.user(), pageable)));
    }
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER')")
    public ApiResponse<LeaveView> approve(@PathVariable UUID id, @Valid @RequestBody LeaveActionRequest request) {
        return ApiResponse.ok("Leave approved", leaves.approve(tenant.companyId(), tenant.user(), id, request));
    }
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER')")
    public ApiResponse<LeaveView> reject(@PathVariable UUID id, @Valid @RequestBody LeaveActionRequest request) {
        return ApiResponse.ok("Leave rejected", leaves.reject(tenant.companyId(), tenant.user(), id, request));
    }
    @PostMapping("/{id}/cancel")
    public ApiResponse<LeaveView> cancel(@PathVariable UUID id, @Valid @RequestBody LeaveActionRequest request) {
        return ApiResponse.ok("Leave cancelled", leaves.cancel(tenant.companyId(), tenant.user(), id, request));
    }

    @GetMapping("/{id}/history")
    public ApiResponse<PageResponse<LeaveStatusHistoryView>> history(@PathVariable UUID id,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        Page<LeaveStatusHistoryView> result = leaves.history(tenant.companyId(), tenant.user(), id, pageable);
        return ApiResponse.ok("Leave status history", PageResponse.from(result));
    }

    @GetMapping("/policies")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR')")
    public ApiResponse<List<LeavePolicyView>> policies() {
        return ApiResponse.ok("Leave policies", ledger.policies(tenant.companyId(), tenant.user()));
    }

    @org.springframework.web.bind.annotation.PutMapping("/policies/{leaveType}")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR')")
    public ApiResponse<LeavePolicyView> setPolicy(@PathVariable LeaveType leaveType,
            @Valid @RequestBody LeavePolicyRequest request) {
        return ApiResponse.ok("Leave policy updated",
                ledger.setPolicy(tenant.companyId(), tenant.user(), leaveType, request));
    }

    @PostMapping("/balances/transactions")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR')")
    public ApiResponse<LeaveBalanceEntryView> addBalanceTransaction(
            @Valid @RequestBody LeaveBalanceTransactionRequest request) {
        return ApiResponse.ok("Leave balance transaction recorded",
                ledger.addTransaction(tenant.companyId(), tenant.user(), request));
    }

    @GetMapping("/balances/mine")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ApiResponse<List<LeaveBalanceView>> myBalances() {
        return ApiResponse.ok("Leave balances", ledger.balances(
                tenant.companyId(), tenant.user(), tenant.user().getEmployeeId()));
    }

    @GetMapping("/balances/{employeeId}")
    public ApiResponse<List<LeaveBalanceView>> balances(@PathVariable UUID employeeId) {
        return ApiResponse.ok("Leave balances",
                ledger.balances(tenant.companyId(), tenant.user(), employeeId));
    }

    @GetMapping("/balances/{employeeId}/ledger")
    public ApiResponse<PageResponse<LeaveBalanceEntryView>> ledgerHistory(@PathVariable UUID employeeId,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("Leave balance ledger", PageResponse.from(
                ledger.history(tenant.companyId(), tenant.user(), employeeId, pageable)));
    }
}
