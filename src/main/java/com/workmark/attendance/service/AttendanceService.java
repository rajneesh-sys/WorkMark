package com.workmark.attendance.service;

import com.workmark.auth.entity.UserRole;
import com.workmark.attendance.dto.AttendanceSummary;
import com.workmark.attendance.dto.AttendanceView;
import com.workmark.attendance.entity.Attendance;
import com.workmark.attendance.entity.AttendanceStatus;
import com.workmark.attendance.repository.AttendanceRepository;
import com.workmark.common.exception.ApiException;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.entity.EmployeeStatus;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.employee.service.EmployeeAccessPolicy;
import com.workmark.security.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Service
public class AttendanceService {
    private final AttendanceRepository attendance;
    private final EmployeeAccessPolicy accessPolicy;
    private final Clock clock;

    public AttendanceService(AttendanceRepository attendance, EmployeeAccessPolicy accessPolicy, Clock clock) {
        this.attendance = attendance;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Transactional
    public AttendanceView checkIn(UUID companyId, CurrentUser user, UUID requestedEmployeeId) {
        accessPolicy.requireTenant(companyId, user);
        UUID employeeId = resolveEmployee(user, requestedEmployeeId);
        activeEmployee(companyId, user, employeeId, true);
        LocalDate today = LocalDate.now(clock);
        if (attendance.findByCompanyIdAndEmployeeIdAndAttendanceDate(companyId, employeeId, today).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Attendance already exists for today");
        }
        try {
            return view(attendance.save(new Attendance(companyId, employeeId, today, Instant.now(clock))));
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Attendance already exists for today");
        }
    }

    @Transactional
    public AttendanceView checkOut(UUID companyId, CurrentUser user, UUID requestedEmployeeId) {
        accessPolicy.requireTenant(companyId, user);
        UUID employeeId = resolveEmployee(user, requestedEmployeeId);
        activeEmployee(companyId, user, employeeId, true);
        LocalDate today = LocalDate.now(clock);
        Attendance record = attendance.findForUpdateByCompanyAndEmployeeAndDate(companyId, employeeId, today)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Today's check-in was not found"));
        if (record.getCheckOutTime() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Attendance has already been checked out");
        }
        Instant checkoutTime = Instant.now(clock);
        if (checkoutTime.isBefore(record.getCheckInTime())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Check-out cannot be before check-in");
        }
        record.checkOut(checkoutTime);
        return view(record);
    }

    @Transactional(readOnly = true)
    public List<AttendanceView> daily(UUID companyId, CurrentUser user, LocalDate date) {
        accessPolicy.requireTenant(companyId, user);
        List<Attendance> records;
        if (user.getRole() == UserRole.COMPANY_ADMIN || user.getRole() == UserRole.HR) {
            records = attendance.findAllByCompanyIdAndAttendanceDateOrderByEmployeeId(companyId, date);
        } else if (user.getRole() == UserRole.MANAGER) {
            if (user.getEmployeeId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Manager account is not linked to an employee");
            }
            records = attendance.findAllForManagerByCompanyAndDate(companyId, user.getEmployeeId(), date);
        } else if (user.getRole() == UserRole.EMPLOYEE) {
            Employee employee = activeEmployee(companyId, user, user.getEmployeeId(), false);
            records = attendance.findAllByCompanyIdAndEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(
                    companyId, employee.getId(), date, date);
        } else {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to view attendance");
        }
        return records.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<AttendanceView> employeeAttendance(UUID companyId, CurrentUser user, UUID requestedEmployeeId,
                                                    LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new ApiException(HttpStatus.BAD_REQUEST, "End date must not precede start date");
        accessPolicy.requireTenant(companyId, user);
        UUID employeeId = resolveEmployee(user, requestedEmployeeId);
        activeEmployee(companyId, user, employeeId, false);
        return attendance.findAllByCompanyIdAndEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(
                companyId, employeeId, from, to).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public AttendanceSummary monthlySummary(UUID companyId, CurrentUser user, UUID requestedEmployeeId,
                                            int year, int month) {
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.of(year, month);
        } catch (java.time.DateTimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid year or month");
        }
        accessPolicy.requireTenant(companyId, user);
        UUID employeeId = resolveEmployee(user, requestedEmployeeId);
        activeEmployee(companyId, user, employeeId, false);
        List<Attendance> entries = attendance.findAllByCompanyIdAndEmployeeIdAndAttendanceDateBetween(
                companyId, employeeId, yearMonth.atDay(1), yearMonth.atEndOfMonth());
        return new AttendanceSummary(employeeId, year, month,
                count(entries, AttendanceStatus.PRESENT), count(entries, AttendanceStatus.ABSENT),
                count(entries, AttendanceStatus.HALF_DAY), count(entries, AttendanceStatus.LATE),
                count(entries, AttendanceStatus.ON_LEAVE),
                entries.stream().map(Attendance::getTotalWorkingMinutes).filter(java.util.Objects::nonNull)
                        .mapToLong(Integer::longValue).sum());
    }

    private UUID resolveEmployee(CurrentUser user, UUID requestedEmployeeId) {
        if (user.getRole() == UserRole.EMPLOYEE) {
            if (user.getEmployeeId() == null || (requestedEmployeeId != null
                    && !user.getEmployeeId().equals(requestedEmployeeId))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Employee account is not linked to this request");
            }
            return user.getEmployeeId();
        }
        if (user.getRole() != UserRole.COMPANY_ADMIN && user.getRole() != UserRole.HR
                && user.getRole() != UserRole.MANAGER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to access attendance");
        }
        if (requestedEmployeeId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "employeeId is required");
        }
        return requestedEmployeeId;
    }

    private Employee activeEmployee(UUID companyId, CurrentUser user, UUID employeeId, boolean forUpdate) {
        Employee employee = forUpdate
                ? accessPolicy.findAccessibleForUpdate(companyId, user, employeeId)
                : accessPolicy.findAccessible(companyId, user, employeeId);
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Inactive employees cannot record attendance");
        }
        return employee;
    }

    private long count(List<Attendance> records, AttendanceStatus status) {
        return records.stream().filter(record -> record.getStatus() == status).count();
    }

    private AttendanceView view(Attendance a) {
        return new AttendanceView(a.getId(), a.getCompanyId(), a.getEmployeeId(), a.getAttendanceDate(),
                a.getCheckInTime(), a.getCheckOutTime(), a.getTotalWorkingMinutes(), a.getStatus());
    }
}
