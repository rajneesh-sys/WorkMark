package com.workmark.attendance.controller;

import com.workmark.attendance.dto.AttendanceActionRequest;
import com.workmark.attendance.dto.AttendanceCorrectionRequest;
import com.workmark.attendance.dto.AttendanceCorrectionView;
import com.workmark.attendance.dto.AttendanceSummary;
import com.workmark.attendance.dto.AttendanceView;
import com.workmark.attendance.service.AttendanceService;
import com.workmark.common.response.ApiResponse;
import com.workmark.common.response.PageResponse;
import com.workmark.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attendance")
@PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER','EMPLOYEE')")
@Tag(name = "Attendance", description = "Daily check-in, check-out, and attendance reporting")
public class AttendanceController {
    private final AttendanceService attendance;
    private final CurrentTenant tenant;
    public AttendanceController(AttendanceService attendance, CurrentTenant tenant) {
        this.attendance = attendance;
        this.tenant = tenant;
    }
    @PostMapping("/check-in")
    @Operation(summary = "Check in for the current company-local attendance date")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AttendanceView> checkIn(@Valid @RequestBody(required = false) AttendanceActionRequest request) {
        UUID employeeId = request == null ? null : request.employeeId();
        return ApiResponse.ok("Checked in", attendance.checkIn(tenant.companyId(), tenant.user(), employeeId));
    }
    @PostMapping("/check-out")
    @Operation(summary = "Check out of the employee's currently open attendance")
    public ApiResponse<AttendanceView> checkOut(@Valid @RequestBody(required = false) AttendanceActionRequest request) {
        UUID employeeId = request == null ? null : request.employeeId();
        return ApiResponse.ok("Checked out", attendance.checkOut(tenant.companyId(), tenant.user(), employeeId));
    }
    @GetMapping("/daily")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER')")
    public ApiResponse<PageResponse<AttendanceView>> daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("Daily attendance", PageResponse.from(
                attendance.daily(tenant.companyId(), tenant.user(), date, pageable)));
    }
    @GetMapping("/employee")
    public ApiResponse<PageResponse<AttendanceView>> employee(
            @RequestParam UUID employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("Employee attendance", PageResponse.from(attendance.employeeAttendance(
                tenant.companyId(), tenant.user(), employeeId, from, to, pageable)));
    }
    @GetMapping("/mine")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ApiResponse<PageResponse<AttendanceView>> mine(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("My attendance", PageResponse.from(attendance.employeeAttendance(
                tenant.companyId(), tenant.user(), tenant.user().getEmployeeId(), from, to, pageable)));
    }
    @GetMapping("/summary/monthly")
    @Operation(summary = "Get monthly attendance totals for one employee")
    public ApiResponse<AttendanceSummary> monthlySummary(
            @RequestParam UUID employeeId, @RequestParam int year, @RequestParam int month) {
        return ApiResponse.ok("Monthly attendance summary", attendance.monthlySummary(
                tenant.companyId(), tenant.user(), employeeId, year, month));
    }

    @PostMapping("/{id}/corrections")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR')")
    @Operation(summary = "Correct an attendance record with an immutable audit entry")
    public ApiResponse<AttendanceView> correct(@PathVariable UUID id,
                                               @Valid @RequestBody AttendanceCorrectionRequest request) {
        return ApiResponse.ok("Attendance corrected",
                attendance.correct(tenant.companyId(), tenant.user(), id, request));
    }

    @GetMapping("/{id}/corrections")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR')")
    @Operation(summary = "View attendance correction history")
    public ApiResponse<PageResponse<AttendanceCorrectionView>> corrections(
            @PathVariable UUID id, @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        Page<AttendanceCorrectionView> result =
                attendance.corrections(tenant.companyId(), tenant.user(), id, pageable);
        return ApiResponse.ok("Attendance correction history", PageResponse.from(result));
    }
}
