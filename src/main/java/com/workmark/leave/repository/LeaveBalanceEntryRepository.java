package com.workmark.leave.repository;

import com.workmark.leave.entity.LeaveBalanceEntry;
import com.workmark.leave.entity.LeaveTransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface LeaveBalanceEntryRepository extends JpaRepository<LeaveBalanceEntry, UUID> {
    @Query(value = """
            select coalesce(sum(delta_days), 0.00)
            from leave_balance_entries
            where company_id = :companyId and employee_id = :employeeId and leave_type = :leaveType
              and effective_at <= :asOf and (expires_at is null or expires_at > :asOf)
            """, nativeQuery = true)
    BigDecimal balance(@Param("companyId") UUID companyId, @Param("employeeId") UUID employeeId,
                       @Param("leaveType") String leaveType, @Param("asOf") Instant asOf);

    @Query(value = """
            select coalesce(sum(delta_days), 0.00)
            from leave_balance_entries
            where company_id = :companyId and employee_id = :employeeId and leave_type = :leaveType
              and transaction_type in ('ALLOCATION', 'ACCRUAL')
              and effective_at >= :from and effective_at < :to
            """, nativeQuery = true)
    BigDecimal annualAllocation(@Param("companyId") UUID companyId, @Param("employeeId") UUID employeeId,
                                @Param("leaveType") String leaveType, @Param("from") Instant from,
                                @Param("to") Instant to);

    @Query(value = """
            select coalesce(sum(delta_days), 0.00)
            from leave_balance_entries
            where company_id = :companyId and employee_id = :employeeId and leave_type = :leaveType
              and transaction_type = 'CARRY_FORWARD'
              and effective_at >= :from and effective_at < :to
            """, nativeQuery = true)
    BigDecimal carryForwardAllocation(@Param("companyId") UUID companyId, @Param("employeeId") UUID employeeId,
                                      @Param("leaveType") String leaveType, @Param("from") Instant from,
                                      @Param("to") Instant to);

    Page<LeaveBalanceEntry> findAllByCompanyIdAndEmployeeIdOrderByEffectiveAtDesc(
            UUID companyId, UUID employeeId, Pageable pageable);

    boolean existsByLeaveRequestIdAndTransactionType(UUID leaveRequestId, LeaveTransactionType transactionType);
    long countByLeaveRequestIdAndTransactionType(UUID leaveRequestId, LeaveTransactionType transactionType);
}
