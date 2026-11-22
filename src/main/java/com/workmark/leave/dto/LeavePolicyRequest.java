package com.workmark.leave.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record LeavePolicyRequest(
        @NotNull @DecimalMin("0.00") @Digits(integer = 5, fraction = 2) BigDecimal annualAllocationDays,
        @NotNull @DecimalMin("0.00") @Digits(integer = 5, fraction = 2) BigDecimal carryForwardLimitDays,
        @Min(0) @Max(120) int carryForwardExpiryMonths,
        boolean allowNegativeBalance,
        boolean active) {}
