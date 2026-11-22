package com.workmark.auth.repository;

import com.workmark.auth.entity.UserAccount;
import com.workmark.auth.entity.UserRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByRole(UserRole role);
    long countByRole(UserRole role);
    List<UserAccount> findAllByCompanyIdAndEmployeeIdAndActiveTrue(UUID companyId, UUID employeeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.activationTokenHash = :tokenHash")
    Optional<UserAccount> findForActivationByTokenHash(@Param("tokenHash") String tokenHash);
}
