package com.workmark.leave.dto;

import com.workmark.leave.entity.LeaveType;

import java.math.BigDecimal;
import java.util.UUID;

public record LeavePolicyView(UUID id, UUID companyId, LeaveType leaveType,
                              BigDecimal annualAllocationDays, BigDecimal carryForwardLimitDays,
                              int carryForwardExpiryMonths, boolean allowNegativeBalance, boolean active) {}
