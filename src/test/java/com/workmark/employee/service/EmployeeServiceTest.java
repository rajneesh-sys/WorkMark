package com.workmark.employee.service;

import com.workmark.auth.entity.UserAccount;
import com.workmark.auth.entity.UserRole;
import com.workmark.auth.repository.UserAccountRepository;
import com.workmark.common.exception.ApiException;
import com.workmark.department.repository.DepartmentRepository;
import com.workmark.employee.dto.EmployeeRequest;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.entity.EmployeeStatus;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeServiceTest {
    @Mock EmployeeRepository employees;
    @Mock DepartmentRepository departments;
    @Mock UserAccountRepository users;

    @Test
    void createEmployeeInTenant() {
        UUID companyId = UUID.randomUUID();
        EmployeeService service = service();
        when(employees.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(companyId, staff(companyId, UserRole.HR), request(null, null));

        assertEquals(companyId, result.companyId());
        assertEquals("E-101", result.employeeCode());
    }

    @Test
    void crossCompanyLookupDoesNotRevealEmployee() {
        UUID companyId = UUID.randomUUID();
        UUID otherCompany = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        when(employees.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());
        EmployeeService service = service();

        ApiException error = assertThrows(ApiException.class,
                () -> service.get(companyId, staff(companyId, UserRole.HR), employeeId));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        verify(employees).findByIdAndCompanyId(employeeId, companyId);
    }

    @Test
    void rejectsDepartmentFromAnotherTenant() {
        UUID companyId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        when(departments.existsByIdAndCompanyId(departmentId, companyId)).thenReturn(false);
        EmployeeService service = service();

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(companyId, staff(companyId, UserRole.HR), request(departmentId, null)));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(employees, never()).save(any());
    }

    @Test
    void managerCannotGetEmployeeOutsideDirectReports() {
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        CurrentUser manager = new CurrentUser(UUID.randomUUID(), companyId, UUID.randomUUID(),
                "manager@example.com", "hash", UserRole.MANAGER, 0);
        when(employees.findByIdAndCompanyIdAndManagerId(employeeId, companyId, manager.getEmployeeId()))
                .thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class,
                () -> service().get(companyId, manager, employeeId));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        verify(employees, never()).findByIdAndCompanyId(employeeId, companyId);
    }

    @Test
    void managerSearchIsConstrainedByManagerIdInTheRepositoryQuery() {
        UUID companyId = UUID.randomUUID();
        UUID managerEmployeeId = UUID.randomUUID();
        CurrentUser manager = new CurrentUser(UUID.randomUUID(), companyId, managerEmployeeId,
                "manager@example.com", "hash", UserRole.MANAGER, 0);
        when(employees.searchDirectReports(eq(companyId), eq(managerEmployeeId), eq("jane"), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service().list(companyId, manager, " jane ", PageRequest.of(0, 20));

        verify(employees).searchDirectReports(eq(companyId), eq(managerEmployeeId), eq("jane"), any());
        verify(employees, never()).search(any(), any(), any());
    }

    @Test
    void employeeCanOnlyReadOwnProfile() {
        UUID companyId = UUID.randomUUID();
        UUID ownEmployeeId = UUID.randomUUID();
        UUID otherEmployeeId = UUID.randomUUID();
        CurrentUser user = new CurrentUser(UUID.randomUUID(), companyId, ownEmployeeId,
                "employee@example.com", "hash", UserRole.EMPLOYEE, 0);

        ApiException error = assertThrows(ApiException.class,
                () -> service().get(companyId, user, otherEmployeeId));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        verifyNoInteractions(employees);
    }

    @Test
    void hrRetainsCompanyWideEmployeeListing() {
        UUID companyId = UUID.randomUUID();
        CurrentUser hr = staff(companyId, UserRole.HR);
        when(employees.findAllByCompanyId(eq(companyId), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service().list(companyId, hr, null, PageRequest.of(0, 20));

        verify(employees).findAllByCompanyId(eq(companyId), any());
    }

    @Test
    void deactivationRevokesLinkedAccountAndReactivationDoesNotRestoreIt() {
        UUID companyId = UUID.randomUUID();
        Employee employee = new Employee(companyId, "E-101", "Jane", "Doe", "jane@example.com",
                null, null, null, null, LocalDate.of(2025, 1, 1));
        UserAccount account = new UserAccount(companyId, "Jane Doe", "jane@example.com",
                "encoded-password", UserRole.EMPLOYEE);
        account.setEmployeeId(employee.getId());
        when(employees.findForUpdateByIdAndCompanyId(employee.getId(), companyId))
                .thenReturn(Optional.of(employee));
        when(users.findAllForUpdateByCompanyIdAndEmployeeId(companyId, employee.getId()))
                .thenReturn(List.of(account));
        EmployeeService service = service();
        CurrentUser hr = staff(companyId, UserRole.HR);

        service.setStatus(companyId, hr, employee.getId(), EmployeeStatus.INACTIVE);

        assertEquals(EmployeeStatus.INACTIVE, employee.getStatus());
        assertFalse(account.isActive());
        assertEquals(1, account.getTokenVersion());

        service.setStatus(companyId, hr, employee.getId(), EmployeeStatus.ACTIVE);

        assertEquals(EmployeeStatus.ACTIVE, employee.getStatus());
        assertFalse(account.isActive());
        assertEquals(1, account.getTokenVersion());
    }

    @Test
    void employeePaginationAddsIdTieBreaker() {
        UUID companyId = UUID.randomUUID();
        CurrentUser hr = staff(companyId, UserRole.HR);
        when(employees.findAllByCompanyId(eq(companyId), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service().list(companyId, hr, null,
                PageRequest.of(0, 20, Sort.by("lastName").ascending()));

        org.mockito.ArgumentCaptor<Pageable> captor =
                org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(employees).findAllByCompanyId(eq(companyId), captor.capture());
        assertEquals("lastName", captor.getValue().getSort().getOrderFor("lastName").getProperty());
        assertEquals("id", captor.getValue().getSort().getOrderFor("id").getProperty());
    }

    @Test
    void employeePaginationRejectsInvalidSortField() {
        UUID companyId = UUID.randomUUID();
        CurrentUser hr = staff(companyId, UserRole.HR);

        ApiException error = assertThrows(ApiException.class,
                () -> service().list(companyId, hr, null,
                        PageRequest.of(0, 20, Sort.by("passwordHash").descending())));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verifyNoInteractions(employees);
    }

    private EmployeeRequest request(UUID departmentId, UUID managerId) {
        return new EmployeeRequest("E-101", "Jane", "Doe", "jane@example.com", null,
                departmentId, managerId, "Analyst", LocalDate.of(2025, 1, 1));
    }

    private CurrentUser staff(UUID companyId, UserRole role) {
        return new CurrentUser(UUID.randomUUID(), companyId, null, "staff@example.com", "hash", role, 0);
    }

    private EmployeeService service() {
        return new EmployeeService(employees, departments, users, new EmployeeAccessPolicy(employees));
    }
}
