package com.workmark.attendance.dto;

import com.workmark.attendance.entity.AttendanceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public record AttendanceCorrectionRequest(@NotNull LocalDate attendanceDate,
                                          @NotNull Instant checkInTime,
                                          Instant checkOutTime,
                                          @NotNull AttendanceStatus status,
                                          @NotBlank @Size(max = 1000) String reason) {}
