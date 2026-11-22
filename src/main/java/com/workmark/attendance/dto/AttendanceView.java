package com.workmark.attendance.dto;

import com.workmark.attendance.entity.AttendanceStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AttendanceView(UUID id, UUID companyId, UUID employeeId, LocalDate attendanceDate,
                             Instant checkInTime, Instant checkOutTime, Integer totalWorkingMinutes,
                             AttendanceStatus status) {}
