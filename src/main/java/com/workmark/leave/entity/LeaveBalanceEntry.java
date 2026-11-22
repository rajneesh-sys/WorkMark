package com.workmark.leave.entity;

import com.workmark.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "leave_balance_entries")
public class LeaveBalanceEntry extends AuditedEntity {
    @Id
    private UUID id;
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;
    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;
    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 20, updatable = false)
    private LeaveType leaveType;
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 24, updatable = false)
    private LeaveTransactionType transactionType;
    @Column(name = "delta_days", nullable = false, precision = 8, scale = 2, updatable = false)
    private BigDecimal deltaDays;
    @Column(name = "leave_request_id", updatable = false)
    private UUID leaveRequestId;
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;
    @Column(nullable = false, length = 1000, updatable = false)
    private String reason;
    @Column(name = "effective_at", nullable = false, updatable = false)
    private Instant effectiveAt;
    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    protected LeaveBalanceEntry() {}

    public LeaveBalanceEntry(UUID companyId, UUID employeeId, LeaveType leaveType,
                             LeaveTransactionType transactionType, BigDecimal deltaDays,
                             UUID leaveRequestId, UUID actorId, String reason,
                             Instant effectiveAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.employeeId = employeeId;
        this.leaveType = leaveType;
        this.transactionType = transactionType;
        this.deltaDays = deltaDays;
        this.leaveRequestId = leaveRequestId;
        this.actorId = actorId;
        this.reason = reason;
        this.effectiveAt = effectiveAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getEmployeeId() { return employeeId; }
    public LeaveType getLeaveType() { return leaveType; }
    public LeaveTransactionType getTransactionType() { return transactionType; }
    public BigDecimal getDeltaDays() { return deltaDays; }
    public UUID getLeaveRequestId() { return leaveRequestId; }
    public UUID getActorId() { return actorId; }
    public String getReason() { return reason; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
