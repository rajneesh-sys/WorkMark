package com.workmark.attendance.entity;

import com.workmark.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "attendance_corrections")
public class AttendanceCorrection extends AuditedEntity {
    @Id
    private UUID id;
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;
    @Column(name = "attendance_id", nullable = false, updatable = false)
    private UUID attendanceId;
    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;
    @Column(name = "corrected_by", nullable = false, updatable = false)
    private UUID correctedBy;
    @Column(nullable = false, length = 1000, updatable = false)
    private String reason;
    @Column(name = "corrected_at", nullable = false, updatable = false)
    private Instant correctedAt;
    @Column(name = "original_attendance_date", nullable = false, updatable = false)
    private LocalDate originalAttendanceDate;
    @Column(name = "original_check_in_time", nullable = false, updatable = false)
    private Instant originalCheckInTime;
    @Column(name = "original_check_out_time", updatable = false)
    private Instant originalCheckOutTime;
    @Column(name = "original_working_minutes", updatable = false)
    private Integer originalWorkingMinutes;
    @Enumerated(EnumType.STRING)
    @Column(name = "original_status", nullable = false, length = 20, updatable = false)
    private AttendanceStatus originalStatus;
    @Column(name = "corrected_attendance_date", nullable = false, updatable = false)
    private LocalDate correctedAttendanceDate;
    @Column(name = "corrected_check_in_time", nullable = false, updatable = false)
    private Instant correctedCheckInTime;
    @Column(name = "corrected_check_out_time", updatable = false)
    private Instant correctedCheckOutTime;
    @Column(name = "corrected_working_minutes", updatable = false)
    private Integer correctedWorkingMinutes;
    @Enumerated(EnumType.STRING)
    @Column(name = "corrected_status", nullable = false, length = 20, updatable = false)
    private AttendanceStatus correctedStatus;

    protected AttendanceCorrection() {}

    public AttendanceCorrection(Attendance attendance, UUID actorId, String reason, Instant correctedAt,
                                AttendanceCorrectionRequestValues corrected) {
        this.id = UUID.randomUUID();
        this.companyId = attendance.getCompanyId();
        this.attendanceId = attendance.getId();
        this.employeeId = attendance.getEmployeeId();
        this.correctedBy = actorId;
        this.reason = reason;
        this.correctedAt = correctedAt;
        this.originalAttendanceDate = attendance.getAttendanceDate();
        this.originalCheckInTime = attendance.getCheckInTime();
        this.originalCheckOutTime = attendance.getCheckOutTime();
        this.originalWorkingMinutes = attendance.getTotalWorkingMinutes();
        this.originalStatus = attendance.getStatus();
        this.correctedAttendanceDate = corrected.attendanceDate();
        this.correctedCheckInTime = corrected.checkInTime();
        this.correctedCheckOutTime = corrected.checkOutTime();
        this.correctedWorkingMinutes = corrected.workingMinutes();
        this.correctedStatus = corrected.status();
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getAttendanceId() { return attendanceId; }
    public UUID getEmployeeId() { return employeeId; }
    public UUID getCorrectedBy() { return correctedBy; }
    public String getReason() { return reason; }
    public Instant getCorrectedAt() { return correctedAt; }
    public LocalDate getOriginalAttendanceDate() { return originalAttendanceDate; }
    public Instant getOriginalCheckInTime() { return originalCheckInTime; }
    public Instant getOriginalCheckOutTime() { return originalCheckOutTime; }
    public Integer getOriginalWorkingMinutes() { return originalWorkingMinutes; }
    public AttendanceStatus getOriginalStatus() { return originalStatus; }
    public LocalDate getCorrectedAttendanceDate() { return correctedAttendanceDate; }
    public Instant getCorrectedCheckInTime() { return correctedCheckInTime; }
    public Instant getCorrectedCheckOutTime() { return correctedCheckOutTime; }
    public Integer getCorrectedWorkingMinutes() { return correctedWorkingMinutes; }
    public AttendanceStatus getCorrectedStatus() { return correctedStatus; }

    public record AttendanceCorrectionRequestValues(LocalDate attendanceDate, Instant checkInTime,
                                                    Instant checkOutTime, Integer workingMinutes,
                                                    AttendanceStatus status) {}
}
