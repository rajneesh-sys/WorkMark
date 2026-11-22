package com.workmark.department.entity;

import com.workmark.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "departments")
public class Department extends AuditedEntity {
    @Id
    private UUID id;
    @Column(name = "company_id", nullable = false)
    private UUID companyId;
    @Column(name = "department_name", nullable = false, length = 120)
    private String departmentName;
    @Column(length = 500)
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DepartmentStatus status;

    protected Department() {}
    public Department(UUID companyId, String name, String description) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.departmentName = name;
        this.description = description;
        this.status = DepartmentStatus.ACTIVE;
    }
    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public String getDepartmentName() { return departmentName; }
    public String getDescription() { return description; }
    public DepartmentStatus getStatus() { return status; }
    public void update(String name, String description) {
        this.departmentName = name;
        this.description = description;
    }
    public void setStatus(DepartmentStatus status) { this.status = status; }
}
