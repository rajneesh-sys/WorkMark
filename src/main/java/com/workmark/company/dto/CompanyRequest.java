package com.workmark.company.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyRequest(@NotBlank @Size(max = 160) String companyName,
                             @NotBlank @Email @Size(max = 254) String email,
                             @Size(max = 40) String phone,
                             @Size(max = 500) String address) {}
