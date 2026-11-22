package com.workmark.auth.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateEmployeeAccountRequest(@NotNull UUID employeeId) {}
