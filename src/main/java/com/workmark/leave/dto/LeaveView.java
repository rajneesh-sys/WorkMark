package com.workmark.leave.dto;

import com.workmark.leave.entity.LeaveStatus;
import com.workmark.leave.entity.LeaveType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LeaveView(UUID id, UUID companyId, UUID employeeId, LeaveType leaveType,
                        LocalDate startDate, LocalDate endDate, BigDecimal numberOfDays,
                        String reason, LeaveStatus status, UUID approvedBy, Instant createdAt) {}
