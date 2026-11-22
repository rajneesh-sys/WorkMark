package com.workmark.attendance.repository;

import com.workmark.attendance.entity.Attendance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {
    Optional<Attendance> findByIdAndCompanyId(UUID id, UUID companyId);
    Optional<Attendance> findByCompanyIdAndEmployeeIdAndAttendanceDate(UUID companyId, UUID employeeId, LocalDate date);
    @Query("""
            select a from Attendance a
            where a.companyId = :companyId and a.employeeId = :employeeId and a.checkOutTime is null
            order by a.attendanceDate desc
            """)
    Optional<Attendance> findOpenByCompanyAndEmployee(@Param("companyId") UUID companyId,
                                                       @Param("employeeId") UUID employeeId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from Attendance a
            where a.companyId = :companyId and a.employeeId = :employeeId and a.checkOutTime is null
            order by a.attendanceDate desc
            """)
    Optional<Attendance> findForUpdateOpenByCompanyAndEmployee(@Param("companyId") UUID companyId,
                                                                @Param("employeeId") UUID employeeId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Attendance a where a.id = :id and a.companyId = :companyId")
    Optional<Attendance> findForUpdateByIdAndCompanyId(@Param("id") UUID id,
                                                        @Param("companyId") UUID companyId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from Attendance a
            where a.companyId = :companyId and a.employeeId = :employeeId and a.attendanceDate = :date
            """)
    Optional<Attendance> findForUpdateByCompanyAndEmployeeAndDate(@Param("companyId") UUID companyId,
                                                                   @Param("employeeId") UUID employeeId,
                                                                   @Param("date") LocalDate date);
    List<Attendance> findAllByCompanyIdAndAttendanceDateOrderByEmployeeId(UUID companyId, LocalDate date);
    Page<Attendance> findAllByCompanyIdAndAttendanceDateOrderByEmployeeId(
            UUID companyId, LocalDate date, Pageable pageable);
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
    @Query(value = """
            select a from Attendance a, Employee e
            where a.companyId = :companyId and a.attendanceDate = :date
              and e.companyId = a.companyId and e.id = a.employeeId and e.managerId = :managerId
            order by a.employeeId
            """,
            countQuery = """
            select count(a) from Attendance a, Employee e
            where a.companyId = :companyId and a.attendanceDate = :date
              and e.companyId = a.companyId and e.id = a.employeeId and e.managerId = :managerId
            """)
    Page<Attendance> findAllForManagerByCompanyAndDate(@Param("companyId") UUID companyId,
                                                       @Param("managerId") UUID managerId,
                                                       @Param("date") LocalDate date,
                                                       Pageable pageable);
    List<Attendance> findAllByCompanyIdAndEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(
            UUID companyId, UUID employeeId, LocalDate from, LocalDate to);
    Page<Attendance> findAllByCompanyIdAndEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(
            UUID companyId, UUID employeeId, LocalDate from, LocalDate to, Pageable pageable);
    List<Attendance> findAllByCompanyIdAndEmployeeIdAndAttendanceDateBetween(
            UUID companyId, UUID employeeId, LocalDate from, LocalDate to);
}
