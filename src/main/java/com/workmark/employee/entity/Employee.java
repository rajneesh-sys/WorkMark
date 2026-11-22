package com.workmark.employee.entity;

import com.workmark.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;
import java.util.Locale;

@Entity
@Table(name = "employees")
public class Employee extends AuditedEntity {
    @Id
    private UUID id;
    @Column(name = "company_id", nullable = false)
    private UUID companyId;
    @Column(name = "employee_code", nullable = false, length = 60)
    private String employeeCode;
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;
    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;
    @Column(nullable = false, length = 254)
    private String email;
    @Column(length = 40)
    private String phone;
    @Column(name = "department_id")
    private UUID departmentId;
    @Column(name = "manager_id")
    private UUID managerId;
    @Column(length = 120)
    private String designation;
    @Column(name = "joining_date", nullable = false)
    private LocalDate joiningDate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmployeeStatus status;

    protected Employee() {}
    public Employee(UUID companyId, String code, String firstName, String lastName, String email,
                    String phone, UUID departmentId, UUID managerId, String designation, LocalDate joiningDate) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.employeeCode = code;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email.toLowerCase(Locale.ROOT);
        this.phone = phone;
        this.departmentId = departmentId;
        this.managerId = managerId;
        this.designation = designation;
        this.joiningDate = joiningDate;
        this.status = EmployeeStatus.ACTIVE;
    }
    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public String getEmployeeCode() { return employeeCode; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public UUID getDepartmentId() { return departmentId; }
    public UUID getManagerId() { return managerId; }
    public String getDesignation() { return designation; }
    public LocalDate getJoiningDate() { return joiningDate; }
    public EmployeeStatus getStatus() { return status; }
    public void update(String code, String firstName, String lastName, String email, String phone,
                       UUID departmentId, UUID managerId, String designation, LocalDate joiningDate) {
        this.employeeCode = code;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email.toLowerCase(Locale.ROOT);
        this.phone = phone;
        this.departmentId = departmentId;
        this.managerId = managerId;
        this.designation = designation;
        this.joiningDate = joiningDate;
    }
    public void setStatus(EmployeeStatus status) { this.status = status; }
}
