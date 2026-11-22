package com.workmark.attendance.dto;

import java.util.UUID;

public record AttendanceSummary(UUID employeeId, int year, int month, long presentDays,
                                long absentDays, long halfDays, long lateDays, long onLeaveDays,
                                long totalWorkingMinutes) {}
