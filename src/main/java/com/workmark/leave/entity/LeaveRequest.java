package com.workmark.leave.entity;

import com.workmark.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "leave_requests")
public class LeaveRequest extends AuditedEntity {
    @Id
    private UUID id;
    @Column(name = "company_id", nullable = false)
    private UUID companyId;
    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;
    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 20)
    private LeaveType leaveType;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;
    @Column(name = "number_of_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal numberOfDays;
    @Column(nullable = false, length = 1000)
    private String reason;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveStatus status;
    @Column(name = "approved_by")
    private UUID approvedBy;

    protected LeaveRequest() {}
    public LeaveRequest(UUID companyId, UUID employeeId, LeaveType type, LocalDate startDate,
                        LocalDate endDate, BigDecimal days, String reason) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.employeeId = employeeId;
        this.leaveType = type;
        this.startDate = startDate;
        this.endDate = endDate;
        this.numberOfDays = days;
        this.reason = reason;
        this.status = LeaveStatus.PENDING;
    }
    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getEmployeeId() { return employeeId; }
    public LeaveType getLeaveType() { return leaveType; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public BigDecimal getNumberOfDays() { return numberOfDays; }
    public String getReason() { return reason; }
    public LeaveStatus getStatus() { return status; }
    public UUID getApprovedBy() { return approvedBy; }
    public void approve(UUID approverId) { status = LeaveStatus.APPROVED; approvedBy = approverId; }
    public void reject(UUID approverId) { status = LeaveStatus.REJECTED; approvedBy = approverId; }
    public void cancel() { status = LeaveStatus.CANCELLED; }
}
