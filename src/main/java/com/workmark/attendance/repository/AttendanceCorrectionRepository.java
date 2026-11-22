package com.workmark.attendance.repository;

import com.workmark.attendance.entity.AttendanceCorrection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AttendanceCorrectionRepository extends JpaRepository<AttendanceCorrection, UUID> {
    Page<AttendanceCorrection> findAllByAttendanceIdAndCompanyIdOrderByCorrectedAtDesc(
            UUID attendanceId, UUID companyId, Pageable pageable);
}
