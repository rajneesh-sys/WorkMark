package com.workmark.company.controller;

import com.workmark.auth.dto.CompanyOnboardingResponse;
import com.workmark.auth.dto.CompanyRegistrationRequest;
import com.workmark.auth.service.AuthService;
import com.workmark.common.response.ApiResponse;
import com.workmark.company.dto.CompanyRequest;
import com.workmark.company.dto.CompanyView;
import com.workmark.company.entity.CompanyStatus;
import com.workmark.company.service.CompanyService;
import com.workmark.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/companies")
@Tag(name = "Companies", description = "Company profile and lifecycle management")
public class CompanyController {
    private final CompanyService companyService;
    private final AuthService authService;
    private final CurrentTenant currentTenant;

    public CompanyController(CompanyService companyService, AuthService authService, CurrentTenant currentTenant) {
        this.companyService = companyService;
        this.authService = authService;
        this.currentTenant = currentTenant;
    }

    @PostMapping
    @Operation(summary = "Register a company and issue its admin a one-time password setup token")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CompanyOnboardingResponse> register(
            @Valid @RequestBody CompanyRegistrationRequest request) {
        return ApiResponse.ok("Company registered; securely deliver the setup token to its administrator",
                authService.registerCompany(currentTenant.user(), request));
    }

    @GetMapping("/me")
    @Operation(summary = "Get the current tenant company")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR','MANAGER','EMPLOYEE')")
    public ApiResponse<CompanyView> getMine() {
        return ApiResponse.ok("Company details", companyService.get(currentTenant.companyId()));
    }

    @PutMapping("/me")
    @Operation(summary = "Update the current tenant company profile")
    @PreAuthorize("hasRole('COMPANY_ADMIN')")
    public ApiResponse<CompanyView> updateMine(@Valid @RequestBody CompanyRequest request) {
        return ApiResponse.ok("Company updated", companyService.update(currentTenant.companyId(), request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate a company")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<CompanyView> setStatus(@PathVariable UUID id, @RequestParam CompanyStatus status) {
        return ApiResponse.ok("Company status updated",
                companyService.setStatus(id, status, currentTenant.user()));
    }
}
