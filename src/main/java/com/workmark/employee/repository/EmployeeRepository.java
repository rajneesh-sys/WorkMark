package com.workmark.employee.repository;

import com.workmark.employee.entity.Employee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
    Optional<Employee> findByIdAndCompanyId(UUID id, UUID companyId);
    Optional<Employee> findByIdAndCompanyIdAndManagerId(UUID id, UUID companyId, UUID managerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e where e.id = :id and e.companyId = :companyId")
    Optional<Employee> findForUpdateByIdAndCompanyId(@Param("id") UUID id, @Param("companyId") UUID companyId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e from Employee e
            where e.id = :id and e.companyId = :companyId and e.managerId = :managerId
            """)
    Optional<Employee> findForUpdateByIdAndCompanyIdAndManagerId(@Param("id") UUID id,
                                                                  @Param("companyId") UUID companyId,
                                                                  @Param("managerId") UUID managerId);
    Page<Employee> findAllByCompanyId(UUID companyId, Pageable pageable);
    Page<Employee> findAllByCompanyIdAndManagerId(UUID companyId, UUID managerId, Pageable pageable);
    Page<Employee> findAllByCompanyIdAndId(UUID companyId, UUID id, Pageable pageable);
    @Query("""
            select e from Employee e
            where e.companyId = :companyId
              and (lower(e.firstName) like lower(concat('%', :query, '%'))
                or lower(e.lastName) like lower(concat('%', :query, '%'))
                or lower(e.email) like lower(concat('%', :query, '%'))
                or lower(e.employeeCode) like lower(concat('%', :query, '%')))
            """)
    Page<Employee> search(@Param("companyId") UUID companyId, @Param("query") String query, Pageable pageable);
    @Query("""
            select e from Employee e
            where e.companyId = :companyId and e.managerId = :managerId
              and (lower(e.firstName) like lower(concat('%', :query, '%'))
                or lower(e.lastName) like lower(concat('%', :query, '%'))
                or lower(e.email) like lower(concat('%', :query, '%'))
                or lower(e.employeeCode) like lower(concat('%', :query, '%')))
            """)
    Page<Employee> searchDirectReports(@Param("companyId") UUID companyId, @Param("managerId") UUID managerId,
                                       @Param("query") String query, Pageable pageable);
    @Query("""
            select e from Employee e
            where e.companyId = :companyId and e.id = :employeeId
              and (lower(e.firstName) like lower(concat('%', :query, '%'))
                or lower(e.lastName) like lower(concat('%', :query, '%'))
                or lower(e.email) like lower(concat('%', :query, '%'))
                or lower(e.employeeCode) like lower(concat('%', :query, '%')))
            """)
    Page<Employee> searchById(@Param("companyId") UUID companyId, @Param("employeeId") UUID employeeId,
                              @Param("query") String query, Pageable pageable);
    boolean existsByCompanyIdAndEmployeeCodeIgnoreCase(UUID companyId, String code);
    boolean existsByCompanyIdAndEmailIgnoreCase(UUID companyId, String email);
    boolean existsByCompanyIdAndEmployeeCodeIgnoreCaseAndIdNot(UUID companyId, String code, UUID id);
    boolean existsByCompanyIdAndEmailIgnoreCaseAndIdNot(UUID companyId, String email, UUID id);
    boolean existsByIdAndCompanyId(UUID id, UUID companyId);
    boolean existsByCompanyIdAndDepartmentId(UUID companyId, UUID departmentId);
}
