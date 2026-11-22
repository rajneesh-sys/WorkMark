package com.workmark.employee.dto;

import com.workmark.employee.entity.EmployeeStatus;

import java.time.LocalDate;
import java.util.UUID;

public record EmployeeView(UUID id, UUID companyId, String employeeCode, String firstName,
                           String lastName, String email, String phone, UUID departmentId,
                           UUID managerId, String designation, LocalDate joiningDate, EmployeeStatus status) {}
