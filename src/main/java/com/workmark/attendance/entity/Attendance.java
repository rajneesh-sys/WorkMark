package com.workmark.attendance.entity;

import com.workmark.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "attendance")
public class Attendance extends AuditedEntity {
    @Id
    private UUID id;
    @Column(name = "company_id", nullable = false)
    private UUID companyId;
    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;
    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;
    @Column(name = "check_in_time", nullable = false)
    private Instant checkInTime;
    @Column(name = "check_out_time")
    private Instant checkOutTime;
    @Column(name = "total_working_minutes")
    private Integer totalWorkingMinutes;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceStatus status;

    protected Attendance() {}
    public Attendance(UUID companyId, UUID employeeId, LocalDate date, Instant checkIn) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.employeeId = employeeId;
        this.attendanceDate = date;
        this.checkInTime = checkIn;
        this.status = AttendanceStatus.PRESENT;
    }
    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getEmployeeId() { return employeeId; }
    public LocalDate getAttendanceDate() { return attendanceDate; }
    public Instant getCheckInTime() { return checkInTime; }
    public Instant getCheckOutTime() { return checkOutTime; }
    public Integer getTotalWorkingMinutes() { return totalWorkingMinutes; }
    public AttendanceStatus getStatus() { return status; }
    public void checkOut(Instant time) {
        checkOutTime = time;
        totalWorkingMinutes = Math.toIntExact(Duration.between(checkInTime, time).toMinutes());
    }
}
