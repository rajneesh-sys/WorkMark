package com.workmark.leave.entity;

import com.workmark.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "leave_policies")
public class LeavePolicy extends AuditedEntity {
    @Id
    private UUID id;
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;
    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 20, updatable = false)
    private LeaveType leaveType;
    @Column(name = "annual_allocation_days", nullable = false, precision = 7, scale = 2)
    private BigDecimal annualAllocationDays;
    @Column(name = "carry_forward_limit_days", nullable = false, precision = 7, scale = 2)
    private BigDecimal carryForwardLimitDays;
    @Column(name = "carry_forward_expiry_months", nullable = false)
    private int carryForwardExpiryMonths;
    @Column(name = "allow_negative_balance", nullable = false)
    private boolean allowNegativeBalance;
    @Column(nullable = false)
    private boolean active;

    protected LeavePolicy() {}

    public LeavePolicy(UUID companyId, LeaveType leaveType, BigDecimal annualAllocationDays,
                       BigDecimal carryForwardLimitDays, int carryForwardExpiryMonths,
                       boolean allowNegativeBalance, boolean active) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.leaveType = leaveType;
        update(annualAllocationDays, carryForwardLimitDays, carryForwardExpiryMonths,
                allowNegativeBalance, active);
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public LeaveType getLeaveType() { return leaveType; }
    public BigDecimal getAnnualAllocationDays() { return annualAllocationDays; }
    public BigDecimal getCarryForwardLimitDays() { return carryForwardLimitDays; }
    public int getCarryForwardExpiryMonths() { return carryForwardExpiryMonths; }
    public boolean isAllowNegativeBalance() { return allowNegativeBalance; }
    public boolean isActive() { return active; }

    public void update(BigDecimal annualAllocationDays, BigDecimal carryForwardLimitDays,
                       int carryForwardExpiryMonths, boolean allowNegativeBalance, boolean active) {
        this.annualAllocationDays = annualAllocationDays;
        this.carryForwardLimitDays = carryForwardLimitDays;
        this.carryForwardExpiryMonths = carryForwardExpiryMonths;
        this.allowNegativeBalance = allowNegativeBalance;
        this.active = active;
    }
}
