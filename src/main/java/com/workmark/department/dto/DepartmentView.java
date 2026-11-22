package com.workmark.department.dto;

import com.workmark.department.entity.DepartmentStatus;

import java.util.UUID;

public record DepartmentView(UUID id, UUID companyId, String departmentName,
                             String description, DepartmentStatus status) {}
