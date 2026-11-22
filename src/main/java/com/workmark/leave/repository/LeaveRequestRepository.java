package com.workmark.leave.repository;

import com.workmark.leave.entity.LeaveRequest;
import com.workmark.leave.entity.LeaveStatus;
import com.workmark.employee.entity.Employee;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {
    Optional<LeaveRequest> findByIdAndCompanyId(UUID id, UUID companyId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from LeaveRequest l where l.id = :id and l.companyId = :companyId")
    Optional<LeaveRequest> findForUpdateByIdAndCompanyId(@Param("id") UUID id,
                                                          @Param("companyId") UUID companyId);
    List<LeaveRequest> findAllByCompanyIdAndEmployeeIdOrderByCreatedAtDesc(UUID companyId, UUID employeeId);
    Page<LeaveRequest> findAllByCompanyIdAndEmployeeIdOrderByCreatedAtDesc(
            UUID companyId, UUID employeeId, Pageable pageable);
    List<LeaveRequest> findAllByCompanyIdAndStatusOrderByCreatedAtAsc(UUID companyId, LeaveStatus status);
    Page<LeaveRequest> findAllByCompanyIdAndStatusOrderByCreatedAtAsc(
            UUID companyId, LeaveStatus status, Pageable pageable);
    @Query("""
            select l from LeaveRequest l, Employee e
            where l.companyId = :companyId
              and e.companyId = l.companyId
              and e.id = l.employeeId
              and e.managerId = :managerId
              and l.status = :status
            order by l.createdAt asc
            """)
    List<LeaveRequest> findAllForManagerByCompanyAndStatus(@Param("companyId") UUID companyId,
                                                           @Param("managerId") UUID managerId,
                                                           @Param("status") LeaveStatus status);
    @Query(value = """
            select l from LeaveRequest l, Employee e
            where l.companyId = :companyId
              and e.companyId = l.companyId and e.id = l.employeeId
              and e.managerId = :managerId and l.status = :status
            order by l.createdAt asc
            """,
            countQuery = """
            select count(l) from LeaveRequest l, Employee e
            where l.companyId = :companyId
              and e.companyId = l.companyId and e.id = l.employeeId
              and e.managerId = :managerId and l.status = :status
            """)
    Page<LeaveRequest> findAllForManagerByCompanyAndStatus(@Param("companyId") UUID companyId,
                                                           @Param("managerId") UUID managerId,
                                                           @Param("status") LeaveStatus status,
                                                           Pageable pageable);
    boolean existsByCompanyIdAndEmployeeIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            UUID companyId, UUID employeeId, List<LeaveStatus> statuses, LocalDate endDate, LocalDate startDate);
    List<LeaveRequest> findAllByCompanyIdAndEmployeeIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            UUID companyId, UUID employeeId, List<LeaveStatus> statuses, LocalDate endDate, LocalDate startDate);
}
