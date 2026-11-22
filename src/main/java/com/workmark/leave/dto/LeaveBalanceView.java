package com.workmark.leave.dto;

import com.workmark.leave.entity.LeaveType;

import java.math.BigDecimal;
import java.util.UUID;

public record LeaveBalanceView(UUID employeeId, LeaveType leaveType, BigDecimal balanceDays,
                               BigDecimal annualAllocationDays, BigDecimal carryForwardLimitDays,
                               int carryForwardExpiryMonths, boolean allowNegativeBalance) {}
