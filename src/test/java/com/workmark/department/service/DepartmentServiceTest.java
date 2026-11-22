package com.workmark.department.service;

import com.workmark.common.exception.ApiException;
import com.workmark.department.dto.DepartmentRequest;
import com.workmark.department.entity.Department;
import com.workmark.department.repository.DepartmentRepository;
import com.workmark.employee.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepartmentServiceTest {
    @Mock DepartmentRepository departments;
    @Mock EmployeeRepository employees;

    @Test
    void createsDepartmentForCurrentCompany() {
        UUID companyId = UUID.randomUUID();
        when(departments.save(any(Department.class))).thenAnswer(invocation -> invocation.getArgument(0));
        DepartmentService service = new DepartmentService(departments, employees);

        var result = service.create(companyId, new DepartmentRequest("People", "People operations"));

        assertEquals(companyId, result.companyId());
        assertEquals("People", result.departmentName());
    }

    @Test
    void departmentWithEmployeesCannotBeDeleted() {
        UUID companyId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(departments.findByIdAndCompanyId(id, companyId))
                .thenReturn(java.util.Optional.of(new Department(companyId, "People", null)));
        when(employees.existsByCompanyIdAndDepartmentId(companyId, id)).thenReturn(true);
        DepartmentService service = new DepartmentService(departments, employees);

        ApiException error = assertThrows(ApiException.class, () -> service.delete(companyId, id));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(departments, never()).delete(any(Department.class));
    }

    @Test
    void departmentListingCapsPageSizeAndAddsStableSort() {
        UUID companyId = UUID.randomUUID();
        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        when(departments.findAllByCompanyId(eq(companyId), any(Pageable.class))).thenReturn(Page.empty());
        DepartmentService service = new DepartmentService(departments, employees);

        service.list(companyId, PageRequest.of(0, 500));

        verify(departments).findAllByCompanyId(eq(companyId), pageable.capture());
        assertEquals(100, pageable.getValue().getPageSize());
        assertEquals("departmentName", pageable.getValue().getSort().getOrderFor("departmentName").getProperty());
        assertEquals("id", pageable.getValue().getSort().getOrderFor("id").getProperty());
    }
}
