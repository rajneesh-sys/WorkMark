package com.workmark.leave.dto;

import com.workmark.leave.entity.LeaveStatus;
import com.workmark.leave.entity.LeaveHistoryAction;

import java.time.Instant;
import java.util.UUID;

public record LeaveStatusHistoryView(UUID id, UUID leaveRequestId, LeaveStatus previousStatus,
                                     LeaveStatus newStatus, UUID actorId, LeaveHistoryAction action,
                                     String comment, Instant actionAt) {}
