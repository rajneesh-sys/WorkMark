package com.workmark.leave.service;

import com.workmark.auth.entity.UserRole;
import com.workmark.common.exception.ApiException;
import com.workmark.company.service.CompanyTimeZoneResolver;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.entity.EmployeeStatus;
import com.workmark.employee.service.EmployeeAccessPolicy;
import com.workmark.leave.dto.LeaveActionRequest;
import com.workmark.leave.dto.LeaveApplyRequest;
import com.workmark.leave.dto.LeaveStatusHistoryView;
import com.workmark.leave.dto.LeaveView;
import com.workmark.leave.entity.LeaveHistoryAction;
import com.workmark.leave.entity.LeaveRequest;
import com.workmark.leave.entity.LeaveStatus;
import com.workmark.leave.entity.LeaveType;
import com.workmark.leave.entity.LeaveStatusHistory;
import com.workmark.leave.repository.LeaveRequestRepository;
import com.workmark.leave.repository.LeaveStatusHistoryRepository;
import com.workmark.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import com.workmark.common.util.SortValidator;

@Service
public class LeaveService {
    private static final Set<String> ALLOWED_LEAVE_SORT_FIELDS =
            Set.of("startDate", "endDate", "numberOfDays", "status", "createdAt", "id");
    private static final Set<String> ALLOWED_HISTORY_SORT_FIELDS =
            Set.of("actionAt", "action", "previousStatus", "newStatus", "id");
    private final LeaveRequestRepository leaveRequests;
    private final LeaveStatusHistoryRepository statusHistory;
    private final EmployeeAccessPolicy accessPolicy;
    private final LeaveLedgerService ledger;
    private final CompanyTimeZoneResolver timeZones;
    private final Clock clock;

    public LeaveService(LeaveRequestRepository leaveRequests, LeaveStatusHistoryRepository statusHistory,
                        EmployeeAccessPolicy accessPolicy, LeaveLedgerService ledger,
                        CompanyTimeZoneResolver timeZones, Clock clock) {
        this.leaveRequests = leaveRequests;
        this.statusHistory = statusHistory;
        this.accessPolicy = accessPolicy;
        this.ledger = ledger;
        this.timeZones = timeZones;
        this.clock = clock;
    }

    @Transactional
    public LeaveView apply(UUID companyId, CurrentUser user, LeaveApplyRequest request) {
        accessPolicy.requireTenant(companyId, user);
        if (request.endDate().isBefore(request.startDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "End date must be on or after start date");
        }
        UUID employeeId = resolveEmployee(user, request.employeeId());
        Employee employee = accessPolicy.findAccessibleForUpdate(companyId, user, employeeId);
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Inactive employees cannot apply for leave");
        }
        boolean overlaps = leaveRequests
                .existsByCompanyIdAndEmployeeIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        companyId, employeeId, List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED),
                        request.endDate(), request.startDate());
        if (overlaps) {
            throw new ApiException(HttpStatus.CONFLICT, "Leave dates overlap an existing pending or approved request");
        }
        BigDecimal days = BigDecimal.valueOf(ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1);
        try {
            LeaveRequest leave = leaveRequests.save(new LeaveRequest(companyId, employeeId, request.leaveType(),
                    request.startDate(), request.endDate(), days, request.reason()));
            recordHistory(companyId, leave, null, LeaveStatus.PENDING, user,
                    LeaveHistoryAction.SUBMITTED, request.reason());
            return view(leave);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Leave dates overlap an existing pending or approved request");
        }
    }

    @Transactional(readOnly = true)
    public Page<LeaveView> employeeRequests(UUID companyId, CurrentUser user, UUID employeeId, Pageable pageable) {
        accessPolicy.requireTenant(companyId, user);
        UUID target = resolveEmployee(user, employeeId);
        accessPolicy.findAccessible(companyId, user, target);
        return leaveRequests.findAllByCompanyIdAndEmployeeIdOrderByCreatedAtDesc(
                        companyId, target, bounded(pageable, Sort.by("createdAt").descending()))
                .map(this::view);
    }

    @Transactional(readOnly = true)
    public Page<LeaveView> pending(UUID companyId, CurrentUser user, Pageable pageable) {
        accessPolicy.requireApprover(companyId, user);
        Pageable bounded = bounded(pageable, Sort.by("createdAt").ascending());
        Page<LeaveRequest> requests;
        if (user.getRole() == UserRole.MANAGER) {
            if (user.getEmployeeId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Manager account is not linked to an employee");
            }
            requests = leaveRequests.findAllForManagerByCompanyAndStatus(
                    companyId, user.getEmployeeId(), LeaveStatus.PENDING, bounded);
        } else {
            requests = leaveRequests.findAllByCompanyIdAndStatusOrderByCreatedAtAsc(
                    companyId, LeaveStatus.PENDING, bounded);
        }
        return requests.map(this::view);
    }

    @Transactional
    public LeaveView approve(UUID companyId, CurrentUser user, UUID id, LeaveActionRequest action) {
        accessPolicy.requireApprover(companyId, user);
        LeaveRequest leave = pendingRequest(companyId, id);
        Employee employee = accessibleEmployeeForApproval(companyId, user, leave);
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Inactive employees' leave cannot be approved");
        }
        LeaveStatus previous = leave.getStatus();
        ledger.consume(companyId, leave.getEmployeeId(), leave.getLeaveType(), leave.getNumberOfDays(),
                leave.getId(), user.getId(), action.comment());
        leave.approve(user.getId());
        recordHistory(companyId, leave, previous, leave.getStatus(), user,
                LeaveHistoryAction.APPROVED, action.comment());
        return view(leave);
    }

    @Transactional
    public LeaveView reject(UUID companyId, CurrentUser user, UUID id, LeaveActionRequest action) {
        accessPolicy.requireApprover(companyId, user);
        LeaveRequest leave = pendingRequest(companyId, id);
        accessibleEmployeeForApproval(companyId, user, leave);
        LeaveStatus previous = leave.getStatus();
        leave.reject(user.getId());
        recordHistory(companyId, leave, previous, leave.getStatus(), user,
                LeaveHistoryAction.REJECTED, action.comment());
        return view(leave);
    }

    @Transactional
    public LeaveView cancel(UUID companyId, CurrentUser user, UUID id, LeaveActionRequest action) {
        accessPolicy.requireTenant(companyId, user);
        LeaveRequest leave = leaveRequests.findForUpdateByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Leave request not found"));
        Employee employee = accessPolicy.findAccessibleForUpdate(companyId, user, leave.getEmployeeId());
        boolean companyStaff = user.getRole() == UserRole.COMPANY_ADMIN || user.getRole() == UserRole.HR;
        boolean owner = user.getRole() == UserRole.EMPLOYEE
                && user.getEmployeeId() != null && user.getEmployeeId().equals(leave.getEmployeeId());
        boolean manager = user.getRole() == UserRole.MANAGER;
        if (!companyStaff && !owner && !manager) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot cancel this leave request");
        }
        if (leave.getStatus() != LeaveStatus.PENDING && leave.getStatus() != LeaveStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "Only pending or approved leave requests can be cancelled");
        }
        if (leave.getStatus() == LeaveStatus.APPROVED
                && !leave.getStartDate().isAfter(LocalDate.now(clock.withZone(timeZones.zoneFor(companyId))))) {
            throw new ApiException(HttpStatus.CONFLICT, "Approved leave can only be cancelled before it starts");
        }
        if (leave.getStatus() == LeaveStatus.APPROVED) {
            ledger.restore(companyId, leave.getEmployeeId(), leave.getLeaveType(), leave.getNumberOfDays(),
                    leave.getId(), user.getId(), action.comment());
        }
        LeaveStatus previous = leave.getStatus();
        leave.cancel();
        recordHistory(companyId, leave, previous, leave.getStatus(), user,
                LeaveHistoryAction.CANCELLED, action.comment());
        return view(leave);
    }

    @Transactional(readOnly = true)
    public Page<LeaveStatusHistoryView> history(UUID companyId, CurrentUser user,
                                                UUID leaveRequestId, Pageable pageable) {
        accessPolicy.requireTenant(companyId, user);
        LeaveRequest leave = leaveRequests.findByIdAndCompanyId(leaveRequestId, companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Leave request not found"));
        accessPolicy.findAccessible(companyId, user, leave.getEmployeeId());
        return statusHistory.findAllByLeaveRequestIdAndCompanyIdOrderByActionAtDesc(
                        leaveRequestId, companyId, bounded(pageable, Sort.by("actionAt").descending()))
                .map(this::historyView);
    }

    private Employee accessibleEmployeeForApproval(UUID companyId, CurrentUser user, LeaveRequest leave) {
        return accessPolicy.findAccessibleForUpdate(companyId, user, leave.getEmployeeId());
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

    private LeaveRequest pendingRequest(UUID companyId, UUID id) {
        LeaveRequest leave = leaveRequests.findForUpdateByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Leave request not found"));
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Leave request is not pending");
        }
        return leave;
    }

    private void recordHistory(UUID companyId, LeaveRequest leave, LeaveStatus previous,
                               LeaveStatus next, CurrentUser actor, LeaveHistoryAction action, String comment) {
        statusHistory.save(new LeaveStatusHistory(companyId, leave.getId(), previous, next,
                actor.getId(), action, comment, clock.instant()));
    }

    private Pageable bounded(Pageable pageable, Sort defaultSort) {
        if (pageable.getPageNumber() < 0 || pageable.getPageSize() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Page must be non-negative and size must be positive");
        }
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : defaultSort;
        SortValidator.validate(sort,
                defaultSort.getOrderFor("actionAt") != null
                        ? ALLOWED_HISTORY_SORT_FIELDS : ALLOWED_LEAVE_SORT_FIELDS,
                "leave");
        if (sort.getOrderFor("id") == null) sort = sort.and(Sort.by("id").ascending());
        return PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100),
                sort);
    }

    private LeaveView view(LeaveRequest leave) {
        return new LeaveView(leave.getId(), leave.getCompanyId(), leave.getEmployeeId(), leave.getLeaveType(),
                leave.getStartDate(), leave.getEndDate(), leave.getNumberOfDays(), leave.getReason(),
                leave.getStatus(), leave.getApprovedBy(), leave.getCreatedAt());
    }

    private LeaveStatusHistoryView historyView(LeaveStatusHistory item) {
        return new LeaveStatusHistoryView(item.getId(), item.getLeaveRequestId(), item.getPreviousStatus(),
                item.getNewStatus(), item.getActorId(), item.getAction(), item.getComment(), item.getActionAt());
    }
}
