package com.workmark.attendance.service;

import com.workmark.auth.entity.UserRole;
import com.workmark.attendance.dto.AttendanceSummary;
import com.workmark.attendance.dto.AttendanceCorrectionRequest;
import com.workmark.attendance.dto.AttendanceCorrectionView;
import com.workmark.attendance.dto.AttendanceView;
import com.workmark.attendance.entity.Attendance;
import com.workmark.attendance.entity.AttendanceCorrection;
import com.workmark.attendance.entity.AttendanceStatus;
import com.workmark.attendance.repository.AttendanceCorrectionRepository;
import com.workmark.attendance.repository.AttendanceRepository;
import com.workmark.common.exception.ApiException;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.entity.EmployeeStatus;
import com.workmark.employee.service.EmployeeAccessPolicy;
import com.workmark.company.service.CompanyTimeZoneResolver;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@Service
public class AttendanceService {
    private final AttendanceRepository attendance;
    private final AttendanceCorrectionRepository corrections;
    private final EmployeeAccessPolicy accessPolicy;
    private final CompanyTimeZoneResolver timeZones;
    private final Clock clock;

    public AttendanceService(AttendanceRepository attendance, AttendanceCorrectionRepository corrections,
                             EmployeeAccessPolicy accessPolicy, CompanyTimeZoneResolver timeZones, Clock clock) {
        this.attendance = attendance;
        this.corrections = corrections;
        this.accessPolicy = accessPolicy;
        this.timeZones = timeZones;
        this.clock = clock;
    }

    @Transactional
    public AttendanceView checkIn(UUID companyId, CurrentUser user, UUID requestedEmployeeId) {
        accessPolicy.requireTenant(companyId, user);
        UUID employeeId = resolveEmployee(user, requestedEmployeeId);
        activeEmployee(companyId, user, employeeId, true);
        LocalDate today = LocalDate.now(clock.withZone(timeZones.zoneFor(companyId)));
        if (attendance.findOpenByCompanyAndEmployee(companyId, employeeId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Check out the existing attendance before checking in again");
        }
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
        Attendance record = attendance.findForUpdateOpenByCompanyAndEmployee(companyId, employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "An open check-in was not found"));
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
    public Page<AttendanceView> daily(UUID companyId, CurrentUser user, LocalDate date, Pageable pageable) {
        accessPolicy.requireTenant(companyId, user);
        Pageable bounded = bounded(pageable, Sort.by("employeeId").ascending());
        Page<Attendance> records;
        if (user.getRole() == UserRole.COMPANY_ADMIN || user.getRole() == UserRole.HR) {
            records = attendance.findAllByCompanyIdAndAttendanceDateOrderByEmployeeId(companyId, date, bounded);
        } else if (user.getRole() == UserRole.MANAGER) {
            if (user.getEmployeeId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Manager account is not linked to an employee");
            }
            records = attendance.findAllForManagerByCompanyAndDate(companyId, user.getEmployeeId(), date, bounded);
        } else if (user.getRole() == UserRole.EMPLOYEE) {
            Employee employee = activeEmployee(companyId, user, user.getEmployeeId(), false);
            records = attendance.findAllByCompanyIdAndEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(
                    companyId, employee.getId(), date, date, bounded(pageable, Sort.by("attendanceDate").descending()));
        } else {
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to view attendance");
        }
        return records.map(this::view);
    }

    @Transactional(readOnly = true)
    public Page<AttendanceView> employeeAttendance(UUID companyId, CurrentUser user, UUID requestedEmployeeId,
                                                    LocalDate from, LocalDate to, Pageable pageable) {
        if (to.isBefore(from)) throw new ApiException(HttpStatus.BAD_REQUEST, "End date must not precede start date");
        accessPolicy.requireTenant(companyId, user);
        UUID employeeId = resolveEmployee(user, requestedEmployeeId);
        activeEmployee(companyId, user, employeeId, false);
        return attendance.findAllByCompanyIdAndEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(
                companyId, employeeId, from, to, bounded(pageable, Sort.by("attendanceDate").descending()))
                .map(this::view);
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

    @Transactional
    public AttendanceView correct(UUID companyId, CurrentUser user, UUID attendanceId,
                                  AttendanceCorrectionRequest request) {
        accessPolicy.requireTenant(companyId, user);
        if (user.getRole() != UserRole.COMPANY_ADMIN && user.getRole() != UserRole.HR) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only company administrators or HR can correct attendance");
        }
        Attendance record = attendance.findForUpdateByIdAndCompanyId(attendanceId, companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Attendance record not found"));
        if (request.checkOutTime() != null && request.checkOutTime().isBefore(request.checkInTime())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Check-out cannot be before check-in");
        }
        Integer minutes = request.checkOutTime() == null ? null
                : Math.toIntExact(java.time.Duration.between(request.checkInTime(), request.checkOutTime()).toMinutes());
        AttendanceCorrection.AttendanceCorrectionRequestValues values =
                new AttendanceCorrection.AttendanceCorrectionRequestValues(request.attendanceDate(),
                        request.checkInTime(), request.checkOutTime(), minutes, request.status());
        corrections.save(new AttendanceCorrection(record, user.getId(), request.reason(), clock.instant(), values));
        record.correct(request.attendanceDate(), request.checkInTime(), request.checkOutTime(), request.status());
        return view(record);
    }

    @Transactional(readOnly = true)
    public Page<AttendanceCorrectionView> corrections(UUID companyId, CurrentUser user,
                                                       UUID attendanceId, Pageable pageable) {
        accessPolicy.requireTenant(companyId, user);
        if (user.getRole() != UserRole.COMPANY_ADMIN && user.getRole() != UserRole.HR) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only company administrators or HR can view corrections");
        }
        if (attendance.findByIdAndCompanyId(attendanceId, companyId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Attendance record not found");
        }
        Pageable bounded = bounded(pageable, Sort.by("correctedAt").descending());
        return corrections.findAllByAttendanceIdAndCompanyIdOrderByCorrectedAtDesc(attendanceId, companyId, bounded)
                .map(this::correctionView);
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

    private AttendanceCorrectionView correctionView(AttendanceCorrection correction) {
        return new AttendanceCorrectionView(correction.getId(), correction.getAttendanceId(),
                correction.getEmployeeId(), correction.getCorrectedBy(), correction.getReason(),
                correction.getCorrectedAt(), correction.getOriginalAttendanceDate(),
                correction.getOriginalCheckInTime(), correction.getOriginalCheckOutTime(),
                correction.getOriginalWorkingMinutes(), correction.getOriginalStatus(),
                correction.getCorrectedAttendanceDate(), correction.getCorrectedCheckInTime(),
                correction.getCorrectedCheckOutTime(), correction.getCorrectedWorkingMinutes(),
                correction.getCorrectedStatus());
    }

    private Pageable bounded(Pageable pageable, Sort defaultSort) {
        if (pageable.getPageNumber() < 0 || pageable.getPageSize() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Page must be non-negative and size must be positive");
        }
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : defaultSort;
        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by("id").ascending());
        }
        return PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100), sort);
    }
}
