package com.workmark.attendance.service;

import com.workmark.auth.entity.UserRole;
import com.workmark.attendance.entity.Attendance;
import com.workmark.attendance.repository.AttendanceRepository;
import com.workmark.common.exception.ApiException;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.service.EmployeeAccessPolicy;
import com.workmark.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {
    @Mock AttendanceRepository attendance;
    @Mock EmployeeAccessPolicy accessPolicy;

    @Test
    void duplicateCheckInForEmployeeDateReturnsConflict() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        Clock clock = Clock.fixed(Instant.parse("2026-11-22T09:00:00Z"), ZoneOffset.UTC);
        when(accessPolicy.findAccessibleForUpdate(eq(companyId), any(), eq(employeeId))).thenReturn(
                activeEmployee(companyId));
        when(attendance.findByCompanyIdAndEmployeeIdAndAttendanceDate(companyId, employeeId,
                LocalDate.of(2026, 11, 22))).thenReturn(Optional.of(new Attendance(companyId, employeeId,
                LocalDate.of(2026, 11, 22), Instant.parse("2026-11-22T08:00:00Z"))));
        AttendanceService service = new AttendanceService(attendance, accessPolicy, clock);

        ApiException error = assertThrows(ApiException.class,
                () -> service.checkIn(companyId, employee(employeeId), null));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(attendance, never()).save(any());
    }

    @Test
    void checkInCreatesAttendanceForCurrentTenantEmployee() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 11, 22);
        Clock clock = Clock.fixed(Instant.parse("2026-11-22T09:00:00Z"), ZoneOffset.UTC);
        when(accessPolicy.findAccessibleForUpdate(eq(companyId), any(), eq(employeeId))).thenReturn(
                activeEmployee(companyId));
        when(attendance.findByCompanyIdAndEmployeeIdAndAttendanceDate(companyId, employeeId, today))
                .thenReturn(Optional.empty());
        when(attendance.save(any(Attendance.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AttendanceService service = new AttendanceService(attendance, accessPolicy, clock);

        var result = service.checkIn(companyId, employee(employeeId), null);

        assertEquals(employeeId, result.employeeId());
        assertEquals(today, result.attendanceDate());
    }

    @Test
    void crossCompanyEmployeeCannotCheckIn() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        when(accessPolicy.findAccessibleForUpdate(eq(companyId), any(), eq(employeeId)))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
        AttendanceService service = new AttendanceService(attendance, accessPolicy, Clock.systemUTC());

        ApiException error = assertThrows(ApiException.class,
                () -> service.checkIn(companyId, employee(employeeId), null));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        verify(attendance, never()).save(any());
    }

    @Test
    void managerCannotCheckInForUnrelatedEmployee() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        CurrentUser manager = new CurrentUser(UUID.randomUUID(), companyId, UUID.randomUUID(),
                "manager@example.com", "hash", UserRole.MANAGER, 0);
        when(accessPolicy.findAccessibleForUpdate(companyId, manager, employeeId))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
        AttendanceService service = new AttendanceService(attendance, accessPolicy, Clock.systemUTC());

        ApiException error = assertThrows(ApiException.class,
                () -> service.checkIn(companyId, manager, employeeId));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        verifyNoInteractions(attendance);
    }

    @Test
    void employeeCannotReadAnotherEmployeesAttendance() {
        UUID companyId = UUID.randomUUID();
        UUID ownEmployeeId = UUID.randomUUID();
        UUID otherEmployeeId = UUID.randomUUID();
        CurrentUser user = new CurrentUser(UUID.randomUUID(), companyId, ownEmployeeId,
                "employee@example.com", "hash", UserRole.EMPLOYEE, 0);
        AttendanceService service = new AttendanceService(attendance, accessPolicy, Clock.systemUTC());

        ApiException error = assertThrows(ApiException.class, () -> service.employeeAttendance(
                companyId, user, otherEmployeeId, LocalDate.now(), LocalDate.now()));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verifyNoInteractions(attendance);
    }

    @Test
    void managerDailyAttendanceUsesDirectReportScopedQuery() {
        UUID companyId = UUID.randomUUID();
        UUID managerEmployeeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 11, 22);
        CurrentUser manager = new CurrentUser(UUID.randomUUID(), companyId, managerEmployeeId,
                "manager@example.com", "hash", UserRole.MANAGER, 0);
        when(attendance.findAllForManagerByCompanyAndDate(companyId, managerEmployeeId, date))
                .thenReturn(java.util.List.of());
        AttendanceService service = new AttendanceService(attendance, accessPolicy, Clock.systemUTC());

        service.daily(companyId, manager, date);

        verify(attendance).findAllForManagerByCompanyAndDate(companyId, managerEmployeeId, date);
        verify(attendance, never()).findAllByCompanyIdAndAttendanceDateOrderByEmployeeId(any(), any());
    }

    @Test
    void hrRetainsCompanyWideDailyAttendance() {
        UUID companyId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 11, 22);
        CurrentUser hr = new CurrentUser(UUID.randomUUID(), companyId, null,
                "hr@example.com", "hash", UserRole.HR, 0);
        when(attendance.findAllByCompanyIdAndAttendanceDateOrderByEmployeeId(companyId, date))
                .thenReturn(java.util.List.of());

        new AttendanceService(attendance, accessPolicy, Clock.systemUTC()).daily(companyId, hr, date);

        verify(attendance).findAllByCompanyIdAndAttendanceDateOrderByEmployeeId(companyId, date);
        verify(attendance, never()).findAllForManagerByCompanyAndDate(any(), any(), any());
    }

    private CurrentUser employee(UUID employeeId) {
        return new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), employeeId,
                "employee@example.com", "hash", UserRole.EMPLOYEE, 0);
    }

    private Employee activeEmployee(UUID companyId) {
        return new Employee(companyId, "E1", "A", "B", "a@b.com", null,
                null, null, null, LocalDate.of(2025, 1, 1));
    }
}
