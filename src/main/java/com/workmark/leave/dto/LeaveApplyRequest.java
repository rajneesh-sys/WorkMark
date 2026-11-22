package com.workmark.leave.dto;

import com.workmark.leave.entity.LeaveType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record LeaveApplyRequest(UUID employeeId,
                                @NotNull LeaveType leaveType,
                                @NotNull LocalDate startDate,
                                @NotNull LocalDate endDate,
                                @NotBlank @Size(max = 1000) String reason) {}
