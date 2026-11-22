package com.workmark.leave.service;

import com.workmark.auth.entity.UserRole;
import com.workmark.common.exception.ApiException;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.leave.dto.LeaveApplyRequest;
import com.workmark.leave.dto.LeaveView;
import com.workmark.leave.entity.LeaveRequest;
import com.workmark.leave.entity.LeaveStatus;
import com.workmark.leave.entity.LeaveType;
import com.workmark.leave.repository.LeaveRequestRepository;
import com.workmark.security.CurrentUser;
import com.workmark.employee.service.EmployeeAccessPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class LeaveService {
    private final LeaveRequestRepository leaveRequests;
    private final EmployeeRepository employees;
    private final EmployeeAccessPolicy accessPolicy;

    public LeaveService(LeaveRequestRepository leaveRequests, EmployeeRepository employees,
                        EmployeeAccessPolicy accessPolicy) {
        this.leaveRequests = leaveRequests;
        this.employees = employees;
        this.accessPolicy = accessPolicy;
    }

    @Transactional
    public LeaveView apply(UUID companyId, CurrentUser user, LeaveApplyRequest request) {
        accessPolicy.requireTenant(companyId, user);
        if (request.endDate().isBefore(request.startDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "End date must be on or after start date");
        }
        UUID employeeId = resolveEmployee(user, request.employeeId());
        Employee employee = findEmployeeForUpdate(companyId, user, employeeId);
        if (employee.getStatus() != com.workmark.employee.entity.EmployeeStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Inactive employees cannot apply for leave");
        }
        List<LeaveRequest> overlaps = leaveRequests
                .findAllByCompanyIdAndEmployeeIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        companyId, employeeId, List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED),
                        request.endDate(), request.startDate());
        if (!overlaps.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "Leave dates overlap an existing pending or approved request");
        }
        BigDecimal days = BigDecimal.valueOf(ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1);
        LeaveRequest leave = leaveRequests.save(new LeaveRequest(companyId, employeeId, request.leaveType(),
                request.startDate(), request.endDate(), days, request.reason()));
        return view(leave);
    }

    @Transactional(readOnly = true)
    public List<LeaveView> employeeRequests(UUID companyId, CurrentUser user, UUID employeeId) {
        accessPolicy.requireTenant(companyId, user);
        UUID target = resolveEmployee(user, employeeId);
        accessPolicy.findAccessible(companyId, user, target);
        return leaveRequests.findAllByCompanyIdAndEmployeeIdOrderByCreatedAtDesc(companyId, target)
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveView> pending(UUID companyId, CurrentUser user) {
        accessPolicy.requireApprover(companyId, user);
        if (user.getRole() == UserRole.MANAGER) {
            if (user.getEmployeeId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Manager account is not linked to an employee");
            }
            return leaveRequests.findAllForManagerByCompanyAndStatus(
                            companyId, user.getEmployeeId(), LeaveStatus.PENDING)
                    .stream().map(this::view).toList();
        }
        List<LeaveRequest> requests =
                leaveRequests.findAllByCompanyIdAndStatusOrderByCreatedAtAsc(companyId, LeaveStatus.PENDING);
        return requests.stream().map(this::view).toList();
    }

    @Transactional
    public LeaveView approve(UUID companyId, CurrentUser user, UUID id) {
        accessPolicy.requireApprover(companyId, user);
        LeaveRequest leave = pendingRequest(companyId, id);
        authorizeApproval(companyId, user, leave);
        leave.approve(user.getId());
        return view(leave);
    }

    @Transactional
    public LeaveView reject(UUID companyId, CurrentUser user, UUID id) {
        accessPolicy.requireApprover(companyId, user);
        LeaveRequest leave = pendingRequest(companyId, id);
        authorizeApproval(companyId, user, leave);
        leave.reject(user.getId());
        return view(leave);
    }

    @Transactional
    public LeaveView cancel(UUID companyId, CurrentUser user, UUID id) {
        accessPolicy.requireTenant(companyId, user);
        LeaveRequest leave = leaveRequests.findForUpdateByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Leave request not found"));
        boolean companyStaff = user.getRole() == UserRole.COMPANY_ADMIN || user.getRole() == UserRole.HR;
        boolean owner = user.getRole() == UserRole.EMPLOYEE
                && user.getEmployeeId() != null && user.getEmployeeId().equals(leave.getEmployeeId());
        if (!companyStaff && !owner) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot cancel this leave request");
        }
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Only pending leave requests can be cancelled");
        }
        leave.cancel();
        return view(leave);
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
            throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to apply for leave");
        }
        if (requestedEmployeeId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "employeeId is required");
        }
        return requestedEmployeeId;
    }

    private void authorizeApproval(UUID companyId, CurrentUser user, LeaveRequest leave) {
        if (user.getRole() == UserRole.COMPANY_ADMIN || user.getRole() == UserRole.HR) return;
        if (user.getRole() == UserRole.MANAGER) {
            var employee = accessPolicy.findAccessibleForUpdate(companyId, user, leave.getEmployeeId());
            if (user.getEmployeeId() != null && user.getEmployeeId().equals(employee.getManagerId())) return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to approve this leave request");
    }

    private LeaveRequest pendingRequest(UUID companyId, UUID id) {
        LeaveRequest leave = leaveRequests.findForUpdateByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Leave request not found"));
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Leave request is not pending");
        }
        return leave;
    }

    private Employee findEmployeeForUpdate(UUID companyId, CurrentUser user, UUID employeeId) {
        return accessPolicy.findAccessibleForUpdate(companyId, user, employeeId);
    }

    private LeaveView view(LeaveRequest leave) {
        return new LeaveView(leave.getId(), leave.getCompanyId(), leave.getEmployeeId(), leave.getLeaveType(),
                leave.getStartDate(), leave.getEndDate(), leave.getNumberOfDays(), leave.getReason(),
                leave.getStatus(), leave.getApprovedBy(), leave.getCreatedAt());
    }
}
