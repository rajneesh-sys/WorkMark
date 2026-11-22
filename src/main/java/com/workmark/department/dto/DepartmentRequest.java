package com.workmark.department.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DepartmentRequest(@NotBlank @Size(max = 120) String departmentName,
                               @Size(max = 500) String description) {}
