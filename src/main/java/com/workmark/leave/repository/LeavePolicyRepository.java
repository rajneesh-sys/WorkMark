package com.workmark.leave.repository;

import com.workmark.leave.entity.LeavePolicy;
import com.workmark.leave.entity.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeavePolicyRepository extends JpaRepository<LeavePolicy, UUID> {
    Optional<LeavePolicy> findByCompanyIdAndLeaveType(UUID companyId, LeaveType leaveType);
    List<LeavePolicy> findAllByCompanyIdOrderByLeaveType(UUID companyId);
}
