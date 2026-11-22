package com.workmark.department.service;

import com.workmark.common.exception.ApiException;
import com.workmark.department.dto.DepartmentRequest;
import com.workmark.department.dto.DepartmentView;
import com.workmark.department.entity.Department;
import com.workmark.department.repository.DepartmentRepository;
import com.workmark.employee.repository.EmployeeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DepartmentService {
    private final DepartmentRepository departments;
    private final EmployeeRepository employees;

    public DepartmentService(DepartmentRepository departments, EmployeeRepository employees) {
        this.departments = departments;
        this.employees = employees;
    }

    @Transactional
    public DepartmentView create(UUID companyId, DepartmentRequest request) {
        if (departments.existsByCompanyIdAndDepartmentNameIgnoreCase(companyId, request.departmentName())) {
            throw new ApiException(HttpStatus.CONFLICT, "Department name already exists");
        }
        return view(departments.save(new Department(companyId, request.departmentName(), request.description())));
    }

    @Transactional
    public DepartmentView update(UUID companyId, UUID id, DepartmentRequest request) {
        Department department = find(companyId, id);
        if (departments.existsByCompanyIdAndDepartmentNameIgnoreCaseAndIdNot(
                companyId, request.departmentName(), id)) {
            throw new ApiException(HttpStatus.CONFLICT, "Department name already exists");
        }
        department.update(request.departmentName(), request.description());
        return view(department);
    }

    @Transactional(readOnly = true)
    public List<DepartmentView> list(UUID companyId) {
        return departments.findAllByCompanyIdOrderByDepartmentName(companyId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public DepartmentView get(UUID companyId, UUID id) { return view(find(companyId, id)); }

    @Transactional
    public void delete(UUID companyId, UUID id) {
        Department department = find(companyId, id);
        if (employees.existsByCompanyIdAndDepartmentId(companyId, id)) {
            throw new ApiException(HttpStatus.CONFLICT, "Department has employees and cannot be deleted");
        }
        departments.delete(department);
    }

    private Department find(UUID companyId, UUID id) {
        return departments.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Department not found"));
    }
    private DepartmentView view(Department d) {
        return new DepartmentView(d.getId(), d.getCompanyId(), d.getDepartmentName(), d.getDescription(), d.getStatus());
    }
}
