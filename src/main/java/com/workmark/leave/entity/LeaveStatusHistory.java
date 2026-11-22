package com.workmark.leave.entity;

import com.workmark.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "leave_status_history")
public class LeaveStatusHistory extends AuditedEntity {
    @Id
    private UUID id;
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;
    @Column(name = "leave_request_id", nullable = false, updatable = false)
    private UUID leaveRequestId;
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20, updatable = false)
    private LeaveStatus previousStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20, updatable = false)
    private LeaveStatus newStatus;
    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private LeaveHistoryAction action;
    @Column(length = 1000, updatable = false)
    private String comment;
    @Column(name = "action_at", nullable = false, updatable = false)
    private Instant actionAt;

    protected LeaveStatusHistory() {}

    public LeaveStatusHistory(UUID companyId, UUID leaveRequestId, LeaveStatus previousStatus,
                              LeaveStatus newStatus, UUID actorId, LeaveHistoryAction action,
                              String comment, Instant actionAt) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.leaveRequestId = leaveRequestId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.actorId = actorId;
        this.action = action;
        this.comment = comment;
        this.actionAt = actionAt;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getLeaveRequestId() { return leaveRequestId; }
    public LeaveStatus getPreviousStatus() { return previousStatus; }
    public LeaveStatus getNewStatus() { return newStatus; }
    public UUID getActorId() { return actorId; }
    public LeaveHistoryAction getAction() { return action; }
    public String getComment() { return comment; }
    public Instant getActionAt() { return actionAt; }
}
