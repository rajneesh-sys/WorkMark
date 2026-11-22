package com.workmark.auth.dto;

import com.workmark.auth.entity.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateCompanyAccountRequest(@NotNull UserRole role,
                                          @Size(max = 160) String displayName,
                                          @Email @Size(max = 254) String email,
                                          UUID employeeId,
                                          @NotBlank @Size(min = 12, max = 72) String initialPassword) {}
