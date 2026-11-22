package com.workmark.auth.controller;

import com.workmark.auth.dto.AuthResponse;
import com.workmark.auth.dto.ActivateAccountRequest;
import com.workmark.auth.dto.CreateEmployeeAccountRequest;
import com.workmark.auth.dto.ChangePasswordRequest;
import com.workmark.auth.dto.CreateCompanyAccountRequest;
import com.workmark.auth.dto.LoginRequest;
import com.workmark.auth.service.AuthService;
import com.workmark.common.response.ApiResponse;
import com.workmark.security.CurrentTenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Credentials, account activation, and session operations")
public class AuthController {
    private final AuthService authService;
    private final CurrentTenant currentTenant;

    public AuthController(AuthService authService, CurrentTenant currentTenant) {
        this.authService = authService;
        this.currentTenant = currentTenant;
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate with email and password")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.ok("Login successful", authService.login(request, servletRequest.getRemoteAddr()));
    }

    @PostMapping("/activate")
    @Operation(summary = "Set a password and activate a company administrator account")
    public ApiResponse<Void> activate(@Valid @RequestBody ActivateAccountRequest request) {
        authService.activateAccount(request);
        return ApiResponse.ok("Account activated; sign in to continue", null);
    }

    @PostMapping("/logout")
    @Operation(summary = "Invalidate all access tokens for the current account")
    public ApiResponse<Void> logout() {
        authService.logout(currentTenant.user());
        return ApiResponse.ok("Logged out successfully", null);
    }

    @GetMapping("/me")
    @Operation(summary = "Get the authenticated user")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<AuthResponse.UserView> current() {
        return ApiResponse.ok("Current user", authService.current(currentTenant.user()));
    }

    @PostMapping("/employee-account")
    @Operation(summary = "Create login credentials for a company employee")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','HR')")
    public ApiResponse<AuthResponse.UserView> createEmployeeAccount(
            @Valid @RequestBody CreateEmployeeAccountRequest request) {
        return ApiResponse.ok("Employee login account created",
                authService.createEmployeeAccount(currentTenant.companyId(), request));
    }

    @PostMapping("/company-account")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('COMPANY_ADMIN')")
    @Operation(summary = "Create a company HR or manager account")
    public ApiResponse<AuthResponse.UserView> createCompanyAccount(
            @Valid @RequestBody CreateCompanyAccountRequest request) {
        return ApiResponse.ok("Company account created",
                authService.createCompanyAccount(currentTenant.companyId(), request));
    }

    @PostMapping("/password")
    @Operation(summary = "Change the current user's password and invalidate existing tokens")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(currentTenant.user(), request);
        return ApiResponse.ok("Password changed; sign in again", null);
    }
}
