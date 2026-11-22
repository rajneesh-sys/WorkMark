package com.workmark.attendance.dto;

import com.workmark.attendance.entity.AttendanceStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AttendanceCorrectionView(UUID id, UUID attendanceId, UUID employeeId, UUID correctedBy,
                                        String reason, Instant correctedAt,
                                        LocalDate originalAttendanceDate, Instant originalCheckInTime,
                                        Instant originalCheckOutTime, Integer originalWorkingMinutes,
                                        AttendanceStatus originalStatus, LocalDate correctedAttendanceDate,
                                        Instant correctedCheckInTime, Instant correctedCheckOutTime,
                                        Integer correctedWorkingMinutes, AttendanceStatus correctedStatus) {}
