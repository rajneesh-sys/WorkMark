package com.workmark.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyRegistrationRequest(
        @NotBlank @Size(max = 160) String companyName,
        @NotBlank @Email @Size(max = 254) String companyEmail,
        @Size(max = 40) String phone,
        @Size(max = 500) String address,
        @NotBlank @Size(max = 160) String adminName,
        @NotBlank @Email @Size(max = 254) String adminEmail) {}
