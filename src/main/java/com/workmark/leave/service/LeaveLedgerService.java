package com.workmark.leave.service;

import com.workmark.auth.entity.UserRole;
import com.workmark.common.exception.ApiException;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.employee.service.EmployeeAccessPolicy;
import com.workmark.leave.dto.LeaveBalanceEntryView;
import com.workmark.leave.dto.LeaveBalanceTransactionRequest;
import com.workmark.leave.dto.LeaveBalanceView;
import com.workmark.leave.dto.LeavePolicyRequest;
import com.workmark.leave.dto.LeavePolicyView;
import com.workmark.leave.entity.LeaveBalanceEntry;
import com.workmark.leave.entity.LeavePolicy;
import com.workmark.leave.entity.LeaveTransactionType;
import com.workmark.leave.entity.LeaveType;
import com.workmark.leave.repository.LeaveBalanceEntryRepository;
import com.workmark.leave.repository.LeavePolicyRepository;
import com.workmark.security.CurrentUser;
import com.workmark.company.service.CompanyTimeZoneResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class LeaveLedgerService {
    private final LeavePolicyRepository policies;
    private final LeaveBalanceEntryRepository entries;
    private final EmployeeRepository employees;
    private final EmployeeAccessPolicy accessPolicy;
    private final CompanyTimeZoneResolver timeZones;
    private final Clock clock;

    public LeaveLedgerService(LeavePolicyRepository policies, LeaveBalanceEntryRepository entries,
                              EmployeeRepository employees, EmployeeAccessPolicy accessPolicy,
                              CompanyTimeZoneResolver timeZones, Clock clock) {
        this.policies = policies;
        this.entries = entries;
        this.employees = employees;
        this.accessPolicy = accessPolicy;
        this.timeZones = timeZones;
        this.clock = clock;
    }

    @Transactional
    public LeavePolicyView setPolicy(UUID companyId, CurrentUser actor, LeaveType leaveType,
                                    LeavePolicyRequest request) {
        requireCompanyStaff(companyId, actor);
        if (request.carryForwardExpiryMonths() != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Carry-forward expiry is not supported; configure 0 months");
        }
        LeavePolicy policy = policies.findByCompanyIdAndLeaveType(companyId, leaveType)
                .orElseGet(() -> new LeavePolicy(companyId, leaveType, request.annualAllocationDays(),
                        request.carryForwardLimitDays(), request.carryForwardExpiryMonths(),
                        request.allowNegativeBalance(), request.active()));
        policy.update(request.annualAllocationDays(), request.carryForwardLimitDays(),
                request.carryForwardExpiryMonths(), request.allowNegativeBalance(), request.active());
        return policyView(policies.save(policy));
    }

    @Transactional(readOnly = true)
    public List<LeavePolicyView> policies(UUID companyId, CurrentUser actor) {
        requireCompanyStaff(companyId, actor);
        return policies.findAllByCompanyIdOrderByLeaveType(companyId).stream().map(this::policyView).toList();
    }

    @Transactional
    public LeaveBalanceEntryView addTransaction(UUID companyId, CurrentUser actor,
                                                LeaveBalanceTransactionRequest request) {
        requireCompanyStaff(companyId, actor);
        Employee employee = employees.findForUpdateByIdAndCompanyId(request.employeeId(), companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
        if (employee.getStatus() != com.workmark.employee.entity.EmployeeStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot change leave balances for an inactive employee");
        }
        LeavePolicy policy = requireActivePolicy(companyId, request.leaveType());
        validateManualTransaction(companyId, request, policy);
        Instant now = clock.instant();
        BigDecimal updatedBalance = balance(companyId, employee.getId(), request.leaveType(), now)
                .add(request.deltaDays());
        if (updatedBalance.signum() < 0 && !policy.isAllowNegativeBalance()) {
            throw new ApiException(HttpStatus.CONFLICT, "The transaction would create a negative leave balance");
        }
        LeaveBalanceEntry entry = entries.save(new LeaveBalanceEntry(companyId, employee.getId(),
                request.leaveType(), request.transactionType(), request.deltaDays(), null, actor.getId(),
                request.reason(), now, null));
        return entryView(entry);
    }

    @Transactional(readOnly = true)
    public List<LeaveBalanceView> balances(UUID companyId, CurrentUser user, UUID employeeId) {
        Employee employee = accessPolicy.findAccessible(companyId, user, employeeId);
        Instant now = clock.instant();
        return Arrays.stream(LeaveType.values()).filter(type -> type != LeaveType.UNPAID)
                .map(type -> balanceView(companyId, employee.getId(), type, now)).toList();
    }

    @Transactional(readOnly = true)
    public Page<LeaveBalanceEntryView> history(UUID companyId, CurrentUser user,
                                              UUID employeeId, Pageable pageable) {
        Employee employee = accessPolicy.findAccessible(companyId, user, employeeId);
        if (pageable.getPageNumber() < 0 || pageable.getPageSize() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Page must be non-negative and size must be positive");
        }
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : Sort.by("effectiveAt").descending();
        if (sort.getOrderFor("id") == null) sort = sort.and(Sort.by("id").ascending());
        Pageable bounded = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100), sort);
        return entries.findAllByCompanyIdAndEmployeeIdOrderByEffectiveAtDesc(companyId, employee.getId(), bounded)
                .map(this::entryView);
    }

    @Transactional
    public void consume(UUID companyId, UUID employeeId, LeaveType leaveType, BigDecimal days,
                        UUID requestId, UUID actorId, String reason) {
        if (leaveType == LeaveType.UNPAID) return;
        if (entries.existsByLeaveRequestIdAndTransactionType(requestId, LeaveTransactionType.CONSUMPTION)) {
            throw new ApiException(HttpStatus.CONFLICT, "Leave balance was already deducted for this request");
        }
        LeavePolicy policy = requireActivePolicy(companyId, leaveType);
        BigDecimal remaining = balance(companyId, employeeId, leaveType, clock.instant()).subtract(days);
        if (remaining.signum() < 0 && !policy.isAllowNegativeBalance()) {
            throw new ApiException(HttpStatus.CONFLICT, "Insufficient leave balance");
        }
        entries.save(new LeaveBalanceEntry(companyId, employeeId, leaveType, LeaveTransactionType.CONSUMPTION,
                days.negate(), requestId, actorId, reason, clock.instant(), null));
    }

    @Transactional
    public void restore(UUID companyId, UUID employeeId, LeaveType leaveType, BigDecimal days,
                        UUID requestId, UUID actorId, String reason) {
        if (leaveType == LeaveType.UNPAID) return;
        if (entries.existsByLeaveRequestIdAndTransactionType(requestId, LeaveTransactionType.RESTORATION)) {
            throw new ApiException(HttpStatus.CONFLICT, "Leave balance was already restored for this request");
        }
        entries.save(new LeaveBalanceEntry(companyId, employeeId, leaveType, LeaveTransactionType.RESTORATION,
                days, requestId, actorId, reason, clock.instant(), null));
    }

    private void validateManualTransaction(UUID companyId, LeaveBalanceTransactionRequest request,
                                           LeavePolicy policy) {
        BigDecimal delta = request.deltaDays();
        LeaveTransactionType type = request.transactionType();
        if (request.leaveType() == LeaveType.UNPAID) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unpaid leave does not use a balance ledger");
        }
        if (delta.signum() == 0
                || (type == LeaveTransactionType.EXPIRY && delta.signum() >= 0)
                || (type != LeaveTransactionType.EXPIRY && type != LeaveTransactionType.MANUAL_ADJUSTMENT
                    && delta.signum() <= 0)
                || type == LeaveTransactionType.CONSUMPTION || type == LeaveTransactionType.RESTORATION) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Transaction type and signed delta are inconsistent");
        }
        Instant now = clock.instant();
        ZoneId zone = timeZones.zoneFor(companyId);
        Year year = Year.from(now.atZone(zone));
        Instant start = year.atDay(1).atStartOfDay(zone).toInstant();
        Instant end = year.plusYears(1).atDay(1).atStartOfDay(zone).toInstant();
        if ((type == LeaveTransactionType.ALLOCATION || type == LeaveTransactionType.ACCRUAL)
                && entries.annualAllocation(companyId, request.employeeId(), request.leaveType().name(), start, end)
                .add(delta).compareTo(policy.getAnnualAllocationDays()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Annual allocation exceeds the configured policy limit");
        }
        if (type == LeaveTransactionType.CARRY_FORWARD
                && entries.carryForwardAllocation(companyId, request.employeeId(), request.leaveType().name(),
                        start, end).add(delta).compareTo(policy.getCarryForwardLimitDays()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Carry-forward exceeds the configured policy limit");
        }
    }

    private LeavePolicy requireActivePolicy(UUID companyId, LeaveType leaveType) {
        return policies.findByCompanyIdAndLeaveType(companyId, leaveType)
                .filter(LeavePolicy::isActive)
                .filter(policy -> policy.getCarryForwardExpiryMonths() == 0)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "An active leave policy must be configured before this leave type can be used"));
    }

    private BigDecimal balance(UUID companyId, UUID employeeId, LeaveType leaveType, Instant asOf) {
        BigDecimal result = entries.balance(companyId, employeeId, leaveType.name(), asOf);
        return result == null ? BigDecimal.ZERO : result;
    }

    private LeaveBalanceView balanceView(UUID companyId, UUID employeeId, LeaveType type, Instant now) {
        LeavePolicy policy = policies.findByCompanyIdAndLeaveType(companyId, type).orElse(null);
        return new LeaveBalanceView(employeeId, type, balance(companyId, employeeId, type, now),
                policy == null ? BigDecimal.ZERO : policy.getAnnualAllocationDays(),
                policy == null ? BigDecimal.ZERO : policy.getCarryForwardLimitDays(),
                policy == null ? 0 : policy.getCarryForwardExpiryMonths(),
                policy != null && policy.isAllowNegativeBalance());
    }

    private void requireCompanyStaff(UUID companyId, CurrentUser actor) {
        accessPolicy.requireTenant(companyId, actor);
        if (actor.getRole() != UserRole.COMPANY_ADMIN && actor.getRole() != UserRole.HR) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only company administrators or HR can manage leave policies");
        }
    }

    private LeavePolicyView policyView(LeavePolicy policy) {
        return new LeavePolicyView(policy.getId(), policy.getCompanyId(), policy.getLeaveType(),
                policy.getAnnualAllocationDays(), policy.getCarryForwardLimitDays(),
                policy.getCarryForwardExpiryMonths(), policy.isAllowNegativeBalance(), policy.isActive());
    }

    private LeaveBalanceEntryView entryView(LeaveBalanceEntry entry) {
        return new LeaveBalanceEntryView(entry.getId(), entry.getEmployeeId(), entry.getLeaveType(),
                entry.getTransactionType(), entry.getDeltaDays(), entry.getLeaveRequestId(), entry.getActorId(),
                entry.getReason(), entry.getEffectiveAt(), entry.getExpiresAt());
    }
}
