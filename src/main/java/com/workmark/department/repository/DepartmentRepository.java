package com.workmark.department.repository;

import com.workmark.department.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {
    Optional<Department> findByIdAndCompanyId(UUID id, UUID companyId);
    Page<Department> findAllByCompanyId(UUID companyId, Pageable pageable);
    boolean existsByCompanyIdAndDepartmentNameIgnoreCase(UUID companyId, String name);
    boolean existsByCompanyIdAndDepartmentNameIgnoreCaseAndIdNot(UUID companyId, String name, UUID id);
    boolean existsByIdAndCompanyId(UUID id, UUID companyId);
}
