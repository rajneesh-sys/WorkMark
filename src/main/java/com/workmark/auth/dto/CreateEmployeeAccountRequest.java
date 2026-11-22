package com.workmark.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateEmployeeAccountRequest(@NotNull UUID employeeId,
                                          @NotBlank @Size(min = 12, max = 72) String initialPassword) {}
