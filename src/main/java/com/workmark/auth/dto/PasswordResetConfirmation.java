package com.workmark.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmation(@NotBlank @Size(min = 40, max = 64) String token,
                                        @NotBlank @Size(min = 12, max = 72) String newPassword) {}
