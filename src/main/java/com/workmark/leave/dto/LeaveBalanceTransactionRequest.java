package com.workmark.leave.dto;

import com.workmark.leave.entity.LeaveTransactionType;
import com.workmark.leave.entity.LeaveType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record LeaveBalanceTransactionRequest(@NotNull UUID employeeId,
                                             @NotNull LeaveType leaveType,
                                             @NotNull LeaveTransactionType transactionType,
                                             @NotNull @Digits(integer = 5, fraction = 2) BigDecimal deltaDays,
                                             @NotBlank @Size(max = 1000) String reason) {}
