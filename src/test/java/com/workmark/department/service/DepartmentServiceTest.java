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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
}
