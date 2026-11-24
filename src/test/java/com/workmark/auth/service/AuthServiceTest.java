package com.workmark.auth.service;

import com.workmark.auth.dto.AuthResponse;
import com.workmark.auth.dto.ActivateAccountRequest;
import com.workmark.auth.dto.CreateCompanyAccountRequest;
import com.workmark.auth.dto.CompanyOnboardingResponse;
import com.workmark.auth.dto.CompanyRegistrationRequest;
import com.workmark.auth.dto.PasswordResetConfirmation;
import com.workmark.auth.entity.UserAccount;
import com.workmark.auth.entity.UserRole;
import com.workmark.auth.repository.UserAccountRepository;
import com.workmark.company.entity.Company;
import com.workmark.company.repository.CompanyRepository;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.entity.EmployeeStatus;
import com.workmark.security.CurrentUser;
import com.workmark.security.JwtService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock CompanyRepository companies;
    @Mock UserAccountRepository users;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthenticationManager authenticationManager;
    @Mock JwtService jwtService;
    @Mock EmployeeRepository employees;
    @Mock ActivationTokenService activationTokens;
    @Mock LoginRateLimiter loginRateLimiter;
    @Mock PasswordResetRateLimiter passwordResetRateLimiter;
    @Mock AccountTokenDelivery tokenDelivery;
    @Mock AccountTokenOutbox tokenOutbox;

    @BeforeEach
    void exposeTokensInUnitTestMode() {
        lenient().when(tokenDelivery.exposesDevelopmentToken()).thenReturn(true);
    }

    @Test
    void companyRegistrationCreatesInactiveAdminWithHashedSetupToken() {
        AuthService service = service();
        Instant expiry = Instant.parse("2026-11-23T00:00:00Z");
        when(activationTokens.issue()).thenReturn(
                new ActivationTokenService.IssuedToken("one-time-secret", "stored-token-hash", expiry));
        when(companies.existsByEmailIgnoreCase("company@example.com")).thenReturn(false);
        when(users.existsByEmailIgnoreCase("admin@example.com")).thenReturn(false);
        when(companies.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(users.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tokenDelivery.deliver(eq("admin@example.com"), eq("Admin User"), eq("one-time-secret"),
                eq(expiry), eq(AccountTokenDelivery.Purpose.PASSWORD_SETUP))).thenReturn("one-time-secret");

        CompanyOnboardingResponse result = service.registerCompany(superAdmin(), new CompanyRegistrationRequest(
                "Acme", "company@example.com", "123", null, "Admin User", "admin@example.com"));

        ArgumentCaptor<UserAccount> adminCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(adminCaptor.capture());
        UserAccount savedAdmin = adminCaptor.getValue();
        assertEquals("one-time-secret", result.passwordSetupToken());
        assertEquals(expiry, result.passwordSetupExpiresAt());
        assertEquals(UserRole.COMPANY_ADMIN, savedAdmin.getRole());
        assertEquals(result.companyId(), savedAdmin.getCompanyId());
        assertEquals("Admin User", savedAdmin.getDisplayName());
        assertEquals("stored-token-hash", savedAdmin.getActivationTokenHash());
        assertNull(savedAdmin.getPasswordHash());
        assertFalse(savedAdmin.isActive());
        verify(companies).save(any(Company.class));
        verify(jwtService, never()).issue(any());
    }

    @Test
    void productionCompanyRegistrationPersistsMailWithoutReturningSetupToken() {
        when(tokenDelivery.exposesDevelopmentToken()).thenReturn(false);
        Instant expiry = Instant.parse("2026-11-23T00:00:00Z");
        when(activationTokens.issue()).thenReturn(
                new ActivationTokenService.IssuedToken("secret", "hashed", expiry));
        when(companies.existsByEmailIgnoreCase("company@example.com")).thenReturn(false);
        when(users.existsByEmailIgnoreCase("admin@example.com")).thenReturn(false);
        when(companies.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(users.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CompanyOnboardingResponse response = service().registerCompany(superAdmin(), new CompanyRegistrationRequest(
                "Acme", "company@example.com", null, null, "Admin User", "admin@example.com"));

        assertNull(response.passwordSetupToken());
        assertNull(response.passwordSetupExpiresAt());
        verify(tokenOutbox).enqueue("admin@example.com", "Admin User", "secret", expiry,
                AccountTokenDelivery.Purpose.PASSWORD_SETUP);
        verify(tokenDelivery, never()).deliver(anyString(), anyString(), anyString(), any(),
                any(AccountTokenDelivery.Purpose.class));
    }

    @Test
    void loginIssuesTokenForAuthenticatedUser() {
        AuthService service = service();
        when(loginRateLimiter.isAllowed("192.0.2.1", "admin@example.com")).thenReturn(true);
        var company = new Company("Acme", "company@example.com", null, null);
        CurrentUser user = new CurrentUser(UUID.randomUUID(), company.getId(), null,
                "admin@example.com", "hash", com.workmark.auth.entity.UserRole.COMPANY_ADMIN, 0);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(user);
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(companies.findById(company.getId())).thenReturn(java.util.Optional.of(company));
        when(jwtService.issue(user)).thenReturn("signed-token");

        AuthResponse result = service.login(new com.workmark.auth.dto.LoginRequest(
                "ADMIN@example.com ", "password"), "192.0.2.1");

        assertEquals("signed-token", result.accessToken());
        assertEquals(user.getId(), result.user().id());
        verify(loginRateLimiter).recordSuccess("admin@example.com");
    }

    @Test
    void badCredentialsAreReturnedAsUnauthorized() {
        AuthService service = service();
        when(loginRateLimiter.isAllowed("192.0.2.2", "bad@example.com")).thenReturn(true);
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad credentials"));

        var error = assertThrows(com.workmark.common.exception.ApiException.class,
                () -> service.login(new com.workmark.auth.dto.LoginRequest("bad@example.com", "wrong"),
                        "192.0.2.2"));

        assertEquals(UNAUTHORIZED, error.getStatus());
        verify(loginRateLimiter).recordFailure("192.0.2.2", "bad@example.com");
    }

    @Test
    void throttledLoginUsesTheSameGenericAuthenticationMessage() {
        when(loginRateLimiter.isAllowed("192.0.2.3", "unknown@example.com")).thenReturn(false);

        var error = assertThrows(com.workmark.common.exception.ApiException.class,
                () -> service().login(new com.workmark.auth.dto.LoginRequest(
                        "unknown@example.com", "wrong"), "192.0.2.3"));

        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertEquals("Invalid email or password", error.getMessage());
        verifyNoInteractions(authenticationManager);
    }

    @Test
    void activationStoresEncodedPasswordAndConsumesToken() {
        AuthService service = service();
        Instant now = Instant.parse("2026-11-22T12:00:00Z");
        UserAccount account = new UserAccount(UUID.randomUUID(), "Admin", "admin@example.com",
                null, com.workmark.auth.entity.UserRole.COMPANY_ADMIN);
        account.requireActivation("token-hash", now.plusSeconds(600));
        when(activationTokens.hash("raw-token")).thenReturn("token-hash");
        when(users.findForActivationByTokenHash("token-hash")).thenReturn(java.util.Optional.of(account));
        when(users.findForUpdateById(account.getId())).thenReturn(java.util.Optional.of(account));
        when(passwordEncoder.encode("LongEnoughPassword123")).thenReturn("bcrypt-hash");

        service.activateAccount(new ActivateAccountRequest("raw-token", "LongEnoughPassword123"));

        assertTrue(account.isActive());
        assertEquals("bcrypt-hash", account.getPasswordHash());
        assertNull(account.getActivationTokenHash());
        assertNull(account.getActivationExpiresAt());
    }

    @Test
    void companyAccountEndpointCannotAssignSuperAdminRole() {
        AuthService service = service();
        UUID companyId = UUID.randomUUID();

        var error = assertThrows(com.workmark.common.exception.ApiException.class,
                () -> service.createCompanyAccount(companyId, companyAdmin(companyId),
                        new CreateCompanyAccountRequest(UserRole.SUPER_ADMIN, null, null, null)));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, error.getStatus());
        verifyNoInteractions(users, employees);
    }

    @Test
    void companyRegistrationRequiresSuperAdminAtServiceLayer() {
        var error = assertThrows(com.workmark.common.exception.ApiException.class,
                () -> service().registerCompany(companyAdmin(UUID.randomUUID()),
                        new CompanyRegistrationRequest("Acme", "company@example.com", null, null,
                                "Admin", "admin@example.com")));
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, error.getStatus());
        verifyNoInteractions(companies, users);
    }

    @Test
    void passwordPolicyRejectsBcryptOverflowFromMultibyteInput() {
        String password = "é".repeat(37);
        assertThrows(com.workmark.common.exception.ApiException.class,
                () -> new PasswordPolicy().validate(password));
    }

    @Test
    void passwordResetConsumesTokenAndInvalidatesPreviouslyIssuedTokens() {
        AuthService service = service();
        UUID companyId = UUID.randomUUID();
        UserAccount account = new UserAccount(companyId, "Admin", "admin@example.com",
                "old-hash", UserRole.COMPANY_ADMIN);
        Instant now = Instant.parse("2026-11-22T12:00:00Z");
        when(passwordResetRateLimiter.isAllowed("192.0.2.10", "admin@example.com")).thenReturn(true);
        when(users.findForUpdateByEmailIgnoreCase("admin@example.com")).thenReturn(java.util.Optional.of(account));
        when(activationTokens.issue()).thenReturn(
                new ActivationTokenService.IssuedToken("one-time-reset-token", "reset-token-hash",
                        now.plusSeconds(1800)));
        when(tokenDelivery.deliver("admin@example.com", "Admin", "one-time-reset-token",
                now.plusSeconds(1800), AccountTokenDelivery.Purpose.PASSWORD_RESET))
                .thenReturn("one-time-reset-token");
        when(activationTokens.hash("one-time-reset-token")).thenReturn("reset-token-hash");
        when(users.findForPasswordResetByTokenHash("reset-token-hash"))
                .thenReturn(java.util.Optional.of(account), java.util.Optional.empty());
        when(passwordEncoder.encode("NewSecurePassword2026")).thenReturn("new-hash");

        var response = service.requestPasswordReset("ADMIN@example.com", "192.0.2.10");
        assertEquals("one-time-reset-token", response.developmentToken());
        int previousVersion = account.getTokenVersion();

        service.resetPassword(new PasswordResetConfirmation("one-time-reset-token", "NewSecurePassword2026"));

        assertEquals("new-hash", account.getPasswordHash());
        assertEquals(previousVersion + 1, account.getTokenVersion());
        assertNull(account.getPasswordResetTokenHash());
        assertNull(account.getPasswordResetExpiresAt());
        assertThrows(com.workmark.common.exception.ApiException.class,
                () -> service.resetPassword(new PasswordResetConfirmation(
                        "one-time-reset-token", "AnotherSecurePassword2026")));
        verify(passwordEncoder).encode("NewSecurePassword2026");
        verify(passwordEncoder, never()).encode("AnotherSecurePassword2026");
    }

    @Test
    void activationRejectsInactiveEmployeeAndReplay() {
        AuthService service = service();
        UUID companyId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-22T12:00:00Z");
        UserAccount account = new UserAccount(companyId, "Employee", "employee@example.com",
                null, UserRole.EMPLOYEE);
        account.setEmployeeId(employeeId);
        account.requireActivation("token-hash", now.plusSeconds(600));
        Employee employee = new Employee(companyId, "E1", "Employee", "One", "employee@example.com",
                null, null, null, null, java.time.LocalDate.of(2025, 1, 1));
        employee.setStatus(EmployeeStatus.INACTIVE);
        when(activationTokens.hash("raw-token")).thenReturn("token-hash");
        when(users.findForActivationByTokenHash("token-hash"))
                .thenReturn(java.util.Optional.of(account), java.util.Optional.empty());
        when(employees.findForUpdateByIdAndCompanyId(employeeId, companyId))
                .thenReturn(java.util.Optional.of(employee));

        assertThrows(com.workmark.common.exception.ApiException.class,
                () -> service.activateAccount(new ActivateAccountRequest("raw-token", "LongEnoughPassword123")));
        assertFalse(account.isActive());
        assertThrows(com.workmark.common.exception.ApiException.class,
                () -> service.activateAccount(new ActivateAccountRequest("raw-token", "LongEnoughPassword123")));
    }

    @Test
    void repeatedPasswordResetRequestDoesNotReplaceValidToken() {
        AuthService service = service();
        UUID companyId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-22T12:00:00Z");
        UserAccount account = new UserAccount(companyId, "Admin", "admin@example.com",
                "old-hash", UserRole.COMPANY_ADMIN);
        when(passwordResetRateLimiter.isAllowed("192.0.2.10", "admin@example.com")).thenReturn(true);
        when(users.findForUpdateByEmailIgnoreCase("admin@example.com"))
                .thenReturn(java.util.Optional.of(account));
        when(activationTokens.issue()).thenReturn(
                new ActivationTokenService.IssuedToken("first-reset-token", "first-reset-hash",
                        now.plusSeconds(1800)));
        when(tokenDelivery.deliver(anyString(), anyString(), anyString(), any(),
                eq(AccountTokenDelivery.Purpose.PASSWORD_RESET))).thenReturn("first-reset-token");

        var first = service.requestPasswordReset("ADMIN@example.com", "192.0.2.10");
        var second = service.requestPasswordReset("ADMIN@example.com", "192.0.2.10");

        assertEquals("first-reset-token", first.developmentToken());
        assertNull(second.developmentToken());
        verify(activationTokens, times(1)).issue();
    }

    @Test
    void productionPasswordResetQueuesMailAndKeepsTheResponseGeneric() {
        when(tokenDelivery.exposesDevelopmentToken()).thenReturn(false);
        UserAccount account = new UserAccount(UUID.randomUUID(), "Admin", "admin@example.com",
                "password-hash", UserRole.COMPANY_ADMIN);
        Instant expiry = Instant.parse("2026-11-22T12:30:00Z");
        when(passwordResetRateLimiter.isAllowed("192.0.2.20", "admin@example.com")).thenReturn(true);
        when(users.findForUpdateByEmailIgnoreCase("admin@example.com"))
                .thenReturn(java.util.Optional.of(account));
        when(activationTokens.issue()).thenReturn(
                new ActivationTokenService.IssuedToken("private-reset-token", "hashed-reset-token", expiry));

        var response = service().requestPasswordReset("admin@example.com", "192.0.2.20");

        assertNull(response.developmentToken());
        verify(tokenOutbox).enqueue("admin@example.com", "Admin", "private-reset-token", expiry,
                AccountTokenDelivery.Purpose.PASSWORD_RESET);
        verify(tokenDelivery, never()).deliver(anyString(), anyString(), anyString(), any(),
                any(AccountTokenDelivery.Purpose.class));
    }

    private CurrentUser superAdmin() {
        return new CurrentUser(UUID.randomUUID(), null, null, "platform@example.com", "hash",
                UserRole.SUPER_ADMIN, 0);
    }

    private CurrentUser companyAdmin(UUID companyId) {
        return new CurrentUser(UUID.randomUUID(), companyId, null, "admin@example.com", "hash",
                UserRole.COMPANY_ADMIN, 0);
    }

    private AuthService service() {
        return new AuthService(companies, users, passwordEncoder, authenticationManager, jwtService,
                employees, activationTokens, tokenDelivery, tokenOutbox, loginRateLimiter,
                passwordResetRateLimiter, new PasswordPolicy(),
                Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC),
                java.time.Duration.ofMinutes(30),
                java.time.Duration.ofSeconds(60),
                3600);
    }
}
