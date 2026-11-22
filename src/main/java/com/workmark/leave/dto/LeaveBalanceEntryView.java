package com.workmark.leave.dto;

import com.workmark.leave.entity.LeaveTransactionType;
import com.workmark.leave.entity.LeaveType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LeaveBalanceEntryView(UUID id, UUID employeeId, LeaveType leaveType,
                                    LeaveTransactionType transactionType, BigDecimal deltaDays,
                                    UUID leaveRequestId, UUID actorId, String reason,
                                    Instant effectiveAt, Instant expiresAt) {}
