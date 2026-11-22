package com.workmark.leave.service;

import com.workmark.auth.entity.UserRole;
import com.workmark.common.exception.ApiException;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.employee.service.EmployeeAccessPolicy;
import com.workmark.leave.dto.LeaveApplyRequest;
import com.workmark.leave.entity.LeaveRequest;
import com.workmark.leave.entity.LeaveStatus;
import com.workmark.leave.entity.LeaveType;
import com.workmark.leave.repository.LeaveRequestRepository;
import com.workmark.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaveServiceTest {
    @Mock LeaveRequestRepository leaves;
    @Mock EmployeeRepository employees;
    @Mock EmployeeAccessPolicy accessPolicy;

    @Test
    void overlappingPendingOrApprovedLeaveIsRejected() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 11, 24);
        Employee employee = new Employee(companyId, "E1", "A", "B", "a@b.com", null,
                null, null, null, start.minusYears(1));
        CurrentUser user = currentUser(companyId, employeeId);
        when(accessPolicy.findAccessibleForUpdate(companyId, user, employeeId)).thenReturn(employee);
        LeaveRequest existing = new LeaveRequest(companyId, employeeId, LeaveType.CASUAL, start,
                start.plusDays(2), java.math.BigDecimal.valueOf(3), "existing");
        when(leaves.findAllByCompanyIdAndEmployeeIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                eq(companyId), eq(employeeId), anyList(), any(), any())).thenReturn(List.of(existing));
        LeaveService service = service();

        ApiException error = assertThrows(ApiException.class, () -> service.apply(companyId, user,
                new LeaveApplyRequest(null, LeaveType.SICK, start.plusDays(1), start.plusDays(3), "medical")));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(leaves, never()).save(any());
    }

    @Test
    void appliesLeaveUsingInclusiveCalendarDays() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 11, 24);
        Employee employee = new Employee(companyId, "E1", "A", "B", "a@b.com", null,
                null, null, null, start.minusYears(1));
        CurrentUser user = currentUser(companyId, employeeId);
        when(accessPolicy.findAccessibleForUpdate(companyId, user, employeeId)).thenReturn(employee);
        when(leaves.findAllByCompanyIdAndEmployeeIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                eq(companyId), eq(employeeId), anyList(), any(), any())).thenReturn(List.of());
        when(leaves.save(any(LeaveRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LeaveService service = service();

        var result = service.apply(companyId, user,
                new LeaveApplyRequest(null, LeaveType.CASUAL, start, start.plusDays(1), "personal"));

        assertEquals(java.math.BigDecimal.valueOf(2), result.numberOfDays());
        assertEquals(LeaveStatus.PENDING, result.status());
    }

    @Test
    void managerCannotApproveLeaveForAnotherManagersEmployee() {
        UUID companyId = UUID.randomUUID();
        UUID approverEmployeeId = UUID.randomUUID();
        UUID otherManagerId = UUID.randomUUID();
        UUID leaveId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        LeaveRequest leave = new LeaveRequest(companyId, employeeId, LeaveType.CASUAL,
                LocalDate.now(), LocalDate.now(), java.math.BigDecimal.ONE, "reason");
        CurrentUser manager = currentManager(companyId, approverEmployeeId);
        when(leaves.findForUpdateByIdAndCompanyId(leaveId, companyId)).thenReturn(Optional.of(leave));
        when(accessPolicy.findAccessibleForUpdate(companyId, manager, employeeId)).thenThrow(
                new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
        LeaveService service = service();

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(companyId, manager, leaveId));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals(LeaveStatus.PENDING, leave.getStatus());
        verify(leaves, never()).save(any());
    }

    @Test
    void managerCannotApplyOnBehalfOfAnUnrelatedEmployee() {
        UUID companyId = UUID.randomUUID();
        UUID managerEmployeeId = UUID.randomUUID();
        UUID unrelatedEmployeeId = UUID.randomUUID();
        CurrentUser manager = currentManager(companyId, managerEmployeeId);
        when(accessPolicy.findAccessibleForUpdate(companyId, manager, unrelatedEmployeeId))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
        LeaveService service = service();
        LocalDate start = LocalDate.of(2026, 11, 24);

        ApiException error = assertThrows(ApiException.class, () -> service.apply(companyId, manager,
                new LeaveApplyRequest(unrelatedEmployeeId, LeaveType.CASUAL,
                        start, start.plusDays(1), "requested")));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        verify(leaves, never()).save(any());
    }

    @Test
    void managerCannotViewUnrelatedEmployeeLeaveHistory() {
        UUID companyId = UUID.randomUUID();
        UUID managerEmployeeId = UUID.randomUUID();
        UUID unrelatedEmployeeId = UUID.randomUUID();
        CurrentUser manager = currentManager(companyId, managerEmployeeId);
        when(accessPolicy.findAccessible(companyId, manager, unrelatedEmployeeId)).thenThrow(
                new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
        LeaveService service = service();

        ApiException error = assertThrows(ApiException.class,
                () -> service.employeeRequests(companyId, manager, unrelatedEmployeeId));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        verify(leaves, never()).findAllByCompanyIdAndEmployeeIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void employeeCannotReadAnotherEmployeesLeaveHistory() {
        UUID companyId = UUID.randomUUID();
        CurrentUser employee = currentUser(companyId, UUID.randomUUID());
        LeaveService service = service();

        ApiException error = assertThrows(ApiException.class,
                () -> service.employeeRequests(companyId, employee, UUID.randomUUID()));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verifyNoInteractions(leaves);
    }

    @Test
    void managerPendingRequestsUseCompanyAndDirectReportQuery() {
        UUID companyId = UUID.randomUUID();
        CurrentUser manager = currentManager(companyId, UUID.randomUUID());
        when(leaves.findAllForManagerByCompanyAndStatus(companyId, manager.getEmployeeId(), LeaveStatus.PENDING))
                .thenReturn(List.of());

        service().pending(companyId, manager);

        verify(leaves).findAllForManagerByCompanyAndStatus(
                companyId, manager.getEmployeeId(), LeaveStatus.PENDING);
        verify(leaves, never()).findAllByCompanyIdAndStatusOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void hrRetainsCompanyWidePendingRequests() {
        UUID companyId = UUID.randomUUID();
        CurrentUser hr = new CurrentUser(UUID.randomUUID(), companyId, null,
                "hr@example.com", "hash", UserRole.HR, 0);
        when(leaves.findAllByCompanyIdAndStatusOrderByCreatedAtAsc(companyId, LeaveStatus.PENDING))
                .thenReturn(List.of());

        service().pending(companyId, hr);

        verify(leaves).findAllByCompanyIdAndStatusOrderByCreatedAtAsc(companyId, LeaveStatus.PENDING);
    }

    private CurrentUser currentUser(UUID companyId, UUID employeeId) {
        return new CurrentUser(UUID.randomUUID(), companyId, employeeId, "employee@example.com",
                "hash", UserRole.EMPLOYEE, 0);
    }
    private CurrentUser currentManager(UUID companyId, UUID employeeId) {
        return new CurrentUser(UUID.randomUUID(), companyId, employeeId, "manager@example.com",
                "hash", UserRole.MANAGER, 0);
    }
    private LeaveService service() {
        return new LeaveService(leaves, employees, accessPolicy);
    }
}
