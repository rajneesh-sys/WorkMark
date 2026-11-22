package com.workmark.company.dto;

import com.workmark.company.entity.CompanyStatus;

import java.time.Instant;
import java.util.UUID;

public record CompanyView(UUID id, String companyName, String email, String phone, String address,
                          String timeZoneId, CompanyStatus status, Instant createdAt, Instant updatedAt) {}
