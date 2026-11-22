package com.workmark.leave.repository;

import com.workmark.leave.entity.LeaveStatusHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LeaveStatusHistoryRepository extends JpaRepository<LeaveStatusHistory, UUID> {
    Page<LeaveStatusHistory> findAllByLeaveRequestIdAndCompanyIdOrderByActionAtDesc(
            UUID leaveRequestId, UUID companyId, Pageable pageable);
}
