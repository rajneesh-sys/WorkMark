package com.workmark.attendance.repository;

import com.workmark.attendance.entity.Attendance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {
    Optional<Attendance> findByIdAndCompanyId(UUID id, UUID companyId);
    Optional<Attendance> findByCompanyIdAndEmployeeIdAndAttendanceDate(UUID companyId, UUID employeeId, LocalDate date);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from Attendance a
            where a.companyId = :companyId and a.employeeId = :employeeId and a.attendanceDate = :date
            """)
    Optional<Attendance> findForUpdateByCompanyAndEmployeeAndDate(@Param("companyId") UUID companyId,
                                                                   @Param("employeeId") UUID employeeId,
                                                                   @Param("date") LocalDate date);
    List<Attendance> findAllByCompanyIdAndAttendanceDateOrderByEmployeeId(UUID companyId, LocalDate date);
    @Query("""
            select a from Attendance a, Employee e
            where a.companyId = :companyId
              and a.attendanceDate = :date
              and e.companyId = a.companyId
              and e.id = a.employeeId
              and e.managerId = :managerId
            order by a.employeeId
            """)
    List<Attendance> findAllForManagerByCompanyAndDate(@Param("companyId") UUID companyId,
                                                       @Param("managerId") UUID managerId,
                                                       @Param("date") LocalDate date);
    List<Attendance> findAllByCompanyIdAndEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(
            UUID companyId, UUID employeeId, LocalDate from, LocalDate to);
    List<Attendance> findAllByCompanyIdAndEmployeeIdAndAttendanceDateBetween(
            UUID companyId, UUID employeeId, LocalDate from, LocalDate to);
}
