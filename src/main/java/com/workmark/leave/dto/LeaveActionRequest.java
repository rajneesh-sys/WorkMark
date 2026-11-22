package com.workmark.leave.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LeaveActionRequest(@NotBlank @Size(max = 1000) String comment) {}
