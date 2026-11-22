package com.workmark.attendance.service;

import com.workmark.auth.entity.UserRole;
import com.workmark.attendance.entity.Attendance;
import com.workmark.attendance.entity.AttendanceStatus;
import com.workmark.attendance.repository.AttendanceCorrectionRepository;
import com.workmark.attendance.repository.AttendanceRepository;
import com.workmark.common.exception.ApiException;
import com.workmark.company.repository.CompanyRepository;
import com.workmark.company.service.CompanyTimeZoneResolver;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.service.EmployeeAccessPolicy;
import com.workmark.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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
    @Mock AttendanceCorrectionRepository corrections;
    @Mock EmployeeAccessPolicy accessPolicy;
    @Mock CompanyRepository companies;

    private CompanyTimeZoneResolver timeZones;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        timeZones = new CompanyTimeZoneResolver(companies);
    }

    @Test
    void duplicateCheckInForEmployeeDateReturnsConflict() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        Clock clock = Clock.fixed(Instant.parse("2026-11-22T09:00:00Z"), ZoneOffset.UTC);
        when(accessPolicy.findAccessibleForUpdate(eq(companyId), any(), eq(employeeId))).thenReturn(
                activeEmployee(companyId));
        when(companies.findById(companyId)).thenReturn(Optional.of(
                new com.workmark.company.entity.Company("Acme", "acme@example.com", null, null)));
        when(attendance.findByCompanyIdAndEmployeeIdAndAttendanceDate(companyId, employeeId,
                LocalDate.of(2026, 11, 22))).thenReturn(Optional.of(new Attendance(companyId, employeeId,
                LocalDate.of(2026, 11, 22), Instant.parse("2026-11-22T08:00:00Z"))));
        AttendanceService service = service(clock);

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
        when(companies.findById(companyId)).thenReturn(Optional.of(
                new com.workmark.company.entity.Company("Acme", "acme@example.com", null, null)));
        when(attendance.findOpenByCompanyAndEmployee(companyId, employeeId)).thenReturn(Optional.empty());
        when(attendance.findByCompanyIdAndEmployeeIdAndAttendanceDate(companyId, employeeId, today))
                .thenReturn(Optional.empty());
        when(attendance.save(any(Attendance.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AttendanceService service = service(clock);

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
        AttendanceService service = service(Clock.systemUTC());

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
        AttendanceService service = service(Clock.systemUTC());

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
        AttendanceService service = service(Clock.systemUTC());

        ApiException error = assertThrows(ApiException.class, () -> service.employeeAttendance(
                companyId, user, otherEmployeeId, LocalDate.now(), LocalDate.now(), PageRequest.of(0, 20)));

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
        when(attendance.findAllForManagerByCompanyAndDate(
                eq(companyId), eq(managerEmployeeId), eq(date), any())).thenReturn(new PageImpl<>(java.util.List.of()));
        AttendanceService service = service(Clock.systemUTC());

        service.daily(companyId, manager, date, PageRequest.of(0, 20));

        verify(attendance).findAllForManagerByCompanyAndDate(
                eq(companyId), eq(managerEmployeeId), eq(date), any());
        verify(attendance, never()).findAllByCompanyIdAndAttendanceDateOrderByEmployeeId(any(), any(), any());
    }

    @Test
    void hrRetainsCompanyWideDailyAttendance() {
        UUID companyId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 11, 22);
        CurrentUser hr = new CurrentUser(UUID.randomUUID(), companyId, null,
                "hr@example.com", "hash", UserRole.HR, 0);
        when(attendance.findAllByCompanyIdAndAttendanceDateOrderByEmployeeId(
                eq(companyId), eq(date), any())).thenReturn(new PageImpl<>(java.util.List.of()));

        service(Clock.systemUTC()).daily(companyId, hr, date, PageRequest.of(0, 20));

        verify(attendance).findAllByCompanyIdAndAttendanceDateOrderByEmployeeId(
                eq(companyId), eq(date), any());
        verify(attendance, never()).findAllForManagerByCompanyAndDate(any(), any(), any(), any());
    }

    private CurrentUser employee(UUID employeeId) {
        return new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), employeeId,
                "employee@example.com", "hash", UserRole.EMPLOYEE, 0);
    }

    private Employee activeEmployee(UUID companyId) {
        return new Employee(companyId, "E1", "A", "B", "a@b.com", null,
                null, null, null, LocalDate.of(2025, 1, 1));
    }

    @Test
    void checkInUsesCompanyLocalDayAndCheckOutMayCrossMidnight() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        CurrentUser user = employee(employeeId);
        when(accessPolicy.findAccessibleForUpdate(companyId, user, employeeId))
                .thenReturn(activeEmployee(companyId));
        var company = new com.workmark.company.entity.Company("Acme", "acme@example.com", null, null);
        company.update("Acme", "acme@example.com", null, null, "Asia/Kolkata");
        when(companies.findById(companyId)).thenReturn(Optional.of(company));
        when(attendance.findOpenByCompanyAndEmployee(companyId, employeeId)).thenReturn(Optional.empty());
        when(attendance.findByCompanyIdAndEmployeeIdAndAttendanceDate(
                companyId, employeeId, LocalDate.of(2026, 11, 23))).thenReturn(Optional.empty());
        Attendance record = new Attendance(companyId, employeeId, LocalDate.of(2026, 11, 23),
                Instant.parse("2026-11-22T18:31:00Z"));
        when(attendance.save(any(Attendance.class))).thenReturn(record);
        AttendanceService checkInService = service(
                Clock.fixed(Instant.parse("2026-11-22T18:31:00Z"), ZoneOffset.UTC));

        var checkedIn = checkInService.checkIn(companyId, user, null);

        assertEquals(LocalDate.of(2026, 11, 23), checkedIn.attendanceDate());
    }

    @Test
    void checkOutFindsPriorBusinessDateOpenRecordAfterMidnight() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        CurrentUser user = employee(employeeId);
        Attendance record = new Attendance(companyId, employeeId, LocalDate.of(2026, 11, 22),
                Instant.parse("2026-11-22T18:29:00Z"));
        when(accessPolicy.findAccessibleForUpdate(companyId, user, employeeId)).thenReturn(activeEmployee(companyId));
        when(attendance.findForUpdateOpenByCompanyAndEmployee(companyId, employeeId)).thenReturn(Optional.of(record));
        AttendanceService service = service(Clock.fixed(
                Instant.parse("2026-11-22T18:31:00Z"), ZoneOffset.UTC));

        var checkedOut = service.checkOut(companyId, user, null);

        assertEquals(LocalDate.of(2026, 11, 22), checkedOut.attendanceDate());
        assertEquals(Instant.parse("2026-11-22T18:31:00Z"), checkedOut.checkOutTime());
        verify(attendance, never()).findForUpdateByCompanyAndEmployeeAndDate(any(), any(), any());
    }

    @Test
    void checkoutDurationUsesElapsedTimeAcrossDaylightSavingTransition() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        CurrentUser user = employee(employeeId);
        Attendance record = new Attendance(companyId, employeeId, LocalDate.of(2026, 3, 8),
                Instant.parse("2026-03-08T09:30:00Z"));
        when(accessPolicy.findAccessibleForUpdate(companyId, user, employeeId)).thenReturn(activeEmployee(companyId));
        when(attendance.findForUpdateOpenByCompanyAndEmployee(companyId, employeeId)).thenReturn(Optional.of(record));
        AttendanceService service = service(Clock.fixed(
                Instant.parse("2026-03-08T10:30:00Z"), ZoneOffset.UTC));

        var checkedOut = service.checkOut(companyId, user, null);

        assertEquals(60, checkedOut.totalWorkingMinutes());
    }

    @Test
    void correctionsPersistAnAuditSnapshotAndUpdateAttendance() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID attendanceId = UUID.randomUUID();
        CurrentUser hr = new CurrentUser(UUID.randomUUID(), companyId, null, "hr@example.com",
                "hash", UserRole.HR, 0);
        Attendance record = new Attendance(companyId, employeeId, LocalDate.of(2026, 11, 22),
                Instant.parse("2026-11-22T08:00:00Z"));
        when(attendance.findForUpdateByIdAndCompanyId(attendanceId, companyId)).thenReturn(Optional.of(record));
        var request = new com.workmark.attendance.dto.AttendanceCorrectionRequest(
                LocalDate.of(2026, 11, 21), Instant.parse("2026-11-21T08:00:00Z"),
                Instant.parse("2026-11-21T16:00:00Z"), AttendanceStatus.PRESENT, "Approved correction");

        var result = service(Clock.systemUTC()).correct(companyId, hr, attendanceId, request);

        assertEquals(LocalDate.of(2026, 11, 21), result.attendanceDate());
        assertEquals(480, result.totalWorkingMinutes());
        verify(corrections).save(argThat(correction ->
                correction.getOriginalAttendanceDate().equals(LocalDate.of(2026, 11, 22))
                        && correction.getCorrectedAttendanceDate().equals(LocalDate.of(2026, 11, 21))
                        && correction.getCorrectedBy().equals(hr.getId())));
    }

    @Test
    void employeesCannotCorrectAttendance() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        CurrentUser employee = employee(employeeId);
        var request = new com.workmark.attendance.dto.AttendanceCorrectionRequest(
                LocalDate.now(), Instant.now(), null, AttendanceStatus.PRESENT, "reason");

        ApiException error = assertThrows(ApiException.class,
                () -> service(Clock.systemUTC()).correct(companyId, employee, UUID.randomUUID(), request));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verifyNoInteractions(attendance, corrections);
    }

    private AttendanceService service(Clock clock) {
        return new AttendanceService(attendance, corrections, accessPolicy, timeZones, clock);
    }
}
