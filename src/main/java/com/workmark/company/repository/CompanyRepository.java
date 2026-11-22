package com.workmark.company.repository;

import com.workmark.company.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CompanyRepository extends JpaRepository<Company, UUID> {
    boolean existsByEmailIgnoreCase(String email);
}
