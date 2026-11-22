package com.workmark.attendance.controller;

import com.workmark.attendance.dto.AttendanceActionRequest;
import com.workmark.attendance.dto.AttendanceSummary;
import com.workmark.attendance.dto.AttendanceView;
import com.workmark.attendance.service.AttendanceService;
import com.workmark.common.response.ApiResponse;
import com.workmark.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
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
    @Operation(summary = "Check in for the current UTC attendance date")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AttendanceView> checkIn(@Valid @RequestBody(required = false) AttendanceActionRequest request) {
        UUID employeeId = request == null ? null : request.employeeId();
        return ApiResponse.ok("Checked in", attendance.checkIn(tenant.companyId(), tenant.user(), employeeId));
    }
    @PostMapping("/check-out")
    @Operation(summary = "Check out for the current UTC attendance date")
    public ApiResponse<AttendanceView> checkOut(@Valid @RequestBody(required = false) AttendanceActionRequest request) {
        UUID employeeId = request == null ? null : request.employeeId();
        return ApiResponse.ok("Checked out", attendance.checkOut(tenant.companyId(), tenant.user(), employeeId));
    }
    @GetMapping("/daily")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER')")
    public ApiResponse<List<AttendanceView>> daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok("Daily attendance", attendance.daily(tenant.companyId(), tenant.user(), date));
    }
    @GetMapping("/employee")
    public ApiResponse<List<AttendanceView>> employee(
            @RequestParam UUID employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok("Employee attendance", attendance.employeeAttendance(
                tenant.companyId(), tenant.user(), employeeId, from, to));
    }
    @GetMapping("/mine")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ApiResponse<List<AttendanceView>> mine(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok("My attendance", attendance.employeeAttendance(
                tenant.companyId(), tenant.user(), tenant.user().getEmployeeId(), from, to));
    }
    @GetMapping("/summary/monthly")
    @Operation(summary = "Get monthly attendance totals for one employee")
    public ApiResponse<AttendanceSummary> monthlySummary(
            @RequestParam UUID employeeId, @RequestParam int year, @RequestParam int month) {
        return ApiResponse.ok("Monthly attendance summary", attendance.monthlySummary(
                tenant.companyId(), tenant.user(), employeeId, year, month));
    }
}
