package com.workmark.company.entity;

import com.workmark.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;
import java.util.Locale;

@Entity
@Table(name = "companies")
public class Company extends AuditedEntity {
    @Id
    private UUID id;
    @Column(name = "company_name", nullable = false, length = 160)
    private String companyName;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(length = 40)
    private String phone;
    @Column(length = 500)
    private String address;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompanyStatus status;

    protected Company() {}
    public Company(String companyName, String email, String phone, String address) {
        this.id = UUID.randomUUID();
        this.companyName = companyName;
        this.email = email.toLowerCase(Locale.ROOT);
        this.phone = phone;
        this.address = address;
        this.status = CompanyStatus.ACTIVE;
    }

    public UUID getId() { return id; }
    public String getCompanyName() { return companyName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getAddress() { return address; }
    public CompanyStatus getStatus() { return status; }
    public void update(String name, String email, String phone, String address) {
        this.companyName = name;
        this.email = email.toLowerCase(Locale.ROOT);
        this.phone = phone;
        this.address = address;
    }
    public void setStatus(CompanyStatus status) { this.status = status; }
}
