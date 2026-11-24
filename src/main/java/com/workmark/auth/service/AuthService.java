package com.workmark.auth.service;

import com.workmark.auth.dto.AuthResponse;
import com.workmark.auth.dto.AccountInvitationResponse;
import com.workmark.auth.dto.ActivateAccountRequest;
import com.workmark.auth.dto.CompanyOnboardingResponse;
import com.workmark.auth.dto.CompanyRegistrationRequest;
import com.workmark.auth.dto.CreateEmployeeAccountRequest;
import com.workmark.auth.dto.ChangePasswordRequest;
import com.workmark.auth.dto.CreateCompanyAccountRequest;
import com.workmark.auth.dto.LoginRequest;
import com.workmark.auth.dto.PasswordResetConfirmation;
import com.workmark.auth.dto.PasswordResetRequestResponse;
import com.workmark.auth.entity.UserAccount;
import com.workmark.auth.entity.UserRole;
import com.workmark.auth.repository.UserAccountRepository;
import com.workmark.common.exception.ApiException;
import com.workmark.company.entity.Company;
import com.workmark.company.entity.CompanyStatus;
import com.workmark.company.repository.CompanyRepository;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.security.CurrentUser;
import com.workmark.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final CompanyRepository companies;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final EmployeeRepository employees;
    private final ActivationTokenService activationTokens;
    private final AccountTokenDelivery tokenDelivery;
    private final LoginRateLimiter loginRateLimiter;
    private final PasswordResetRateLimiter passwordResetRateLimiter;
    private final PasswordPolicy passwordPolicy;
    private final Clock clock;
    private final Duration passwordResetTtl;
    private final Duration passwordResetCooldown;
    private final long expirationSeconds;

    public AuthService(CompanyRepository companies, UserAccountRepository users, PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager, JwtService jwtService,
                       EmployeeRepository employees, ActivationTokenService activationTokens,
                       AccountTokenDelivery tokenDelivery, LoginRateLimiter loginRateLimiter,
                       PasswordResetRateLimiter passwordResetRateLimiter,
                       PasswordPolicy passwordPolicy, Clock clock,
                       @Value("${app.auth.password-reset-ttl:PT30M}") Duration passwordResetTtl,
                       @Value("${app.auth.password-reset-cooldown:PT60S}") Duration passwordResetCooldown,
                       @Value("${app.jwt.expiration-seconds:3600}") long expirationSeconds) {
        this.companies = companies;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.employees = employees;
        this.activationTokens = activationTokens;
        this.tokenDelivery = tokenDelivery;
        this.loginRateLimiter = loginRateLimiter;
        this.passwordResetRateLimiter = passwordResetRateLimiter;
        this.passwordPolicy = passwordPolicy;
        this.clock = clock;
        if (passwordResetTtl.isZero() || passwordResetTtl.isNegative()) {
            throw new IllegalArgumentException("Password reset token TTL must be positive");
        }
        this.passwordResetTtl = passwordResetTtl;
        if (passwordResetCooldown.isNegative() || passwordResetCooldown.isZero()) {
            throw new IllegalArgumentException("Password reset cooldown must be positive");
        }
        this.passwordResetCooldown = passwordResetCooldown;
        this.expirationSeconds = expirationSeconds;
    }

    @Transactional
    public CompanyOnboardingResponse registerCompany(CurrentUser actor, CompanyRegistrationRequest request) {
        if (actor.getRole() != UserRole.SUPER_ADMIN || actor.getCompanyId() != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a Super Admin can register companies");
        }
        if (companies.existsByEmailIgnoreCase(request.companyEmail())
                || users.existsByEmailIgnoreCase(request.adminEmail())) {
            throw new ApiException(HttpStatus.CONFLICT, "Company or user email is already registered");
        }
        Company company = companies.save(new Company(request.companyName(), request.companyEmail(),
                request.phone(), request.address()));
        ActivationTokenService.IssuedToken token = activationTokens.issue();
        UserAccount user = new UserAccount(company.getId(), request.adminName(), request.adminEmail(),
                null, UserRole.COMPANY_ADMIN);
        user.requireActivation(token.hash(), token.expiresAt());
        users.save(user);
        String exposedToken = tokenDelivery.deliver(user.getEmail(), user.getDisplayName(), token.value(),
                token.expiresAt(), AccountTokenDelivery.Purpose.PASSWORD_SETUP);
        return new CompanyOnboardingResponse(company.getId(), company.getCompanyName(), user.getId(),
                user.getEmail(), exposedToken, exposedToken == null ? null : token.expiresAt());
    }

    @Transactional
    public void activateAccount(ActivateAccountRequest request) {
        passwordPolicy.validate(request.password());
        String tokenHash = activationTokens.hash(request.token());
        UserAccount candidate = users.findForActivationByTokenHash(tokenHash)
                .filter(account -> account.getActivationExpiresAt().isAfter(clock.instant()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Activation token is invalid or expired"));
        if (candidate.getRole() == UserRole.EMPLOYEE || candidate.getRole() == UserRole.MANAGER) {
            if (candidate.getCompanyId() == null || candidate.getEmployeeId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Activation token is not associated with an active employee");
            }
            Employee employee = employees.findForUpdateByIdAndCompanyId(candidate.getEmployeeId(),
                            candidate.getCompanyId())
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                            "Activation token is invalid or the employee no longer exists"));
            if (employee.getStatus() != com.workmark.employee.entity.EmployeeStatus.ACTIVE) {
                throw new ApiException(HttpStatus.CONFLICT, "The employee account is inactive");
            }
        }
        UserAccount user = users.findForUpdateById(candidate.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Activation token is invalid or expired"));
        if (user.getActivationTokenHash() == null
                || !user.getActivationTokenHash().equals(tokenHash)
                || user.getActivationExpiresAt() == null
                || !user.getActivationExpiresAt().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Activation token is invalid or expired");
        }
        user.activate(passwordEncoder.encode(request.password()));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request, String remoteAddress) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        if (!loginRateLimiter.isAllowed(remoteAddress, normalizedEmail)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Invalid email or password");
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedEmail, request.password()));
            CurrentUser user = (CurrentUser) authentication.getPrincipal();
            loginRateLimiter.recordSuccess(normalizedEmail);
            if (user.getCompanyId() != null && companies.findById(user.getCompanyId())
                    .map(company -> company.getStatus() != CompanyStatus.ACTIVE).orElse(true)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Company account is inactive");
            }
            return response(user);
        } catch (AuthenticationException exception) {
            loginRateLimiter.recordFailure(remoteAddress, normalizedEmail);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
    }

    @Transactional
    public void logout(CurrentUser currentUser) {
        UserAccount user = users.findById(currentUser.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found"));
        user.invalidateTokens();
    }

    @Transactional
    public void reactivateAccount(UUID companyId, CurrentUser actor, UUID accountId) {
        requireCompanyRole(companyId, actor, UserRole.COMPANY_ADMIN);
        UserAccount account = users.findForUpdateByIdAndCompanyId(accountId, companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        if (account.getPasswordHash() == null || account.getActivationTokenHash() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "The account must have a usable setup state");
        }
        if (account.getEmployeeId() != null) {
            Employee employee = employees.findForUpdateByIdAndCompanyId(account.getEmployeeId(), companyId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "The linked employee no longer exists"));
            if (employee.getStatus() != com.workmark.employee.entity.EmployeeStatus.ACTIVE) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Cannot reactivate an account for an inactive employee");
            }
        }
        account.reactivate();
    }

    public AuthResponse.UserView current(CurrentUser user) {
        return view(user);
    }

    @Transactional
    public AccountInvitationResponse createEmployeeAccount(UUID companyId, CurrentUser actor,
                                                           CreateEmployeeAccountRequest request) {
        requireCompanyRole(companyId, actor, UserRole.COMPANY_ADMIN, UserRole.HR);
        Employee employee = employees.findForUpdateByIdAndCompanyId(request.employeeId(), companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
        if (employee.getStatus() != com.workmark.employee.entity.EmployeeStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot create an account for an inactive employee");
        }
        if (users.existsByEmailIgnoreCase(employee.getEmail())) {
            throw new ApiException(HttpStatus.CONFLICT, "An account already uses this employee email");
        }
        ActivationTokenService.IssuedToken token = activationTokens.issue();
        UserAccount account = new UserAccount(companyId,
                employee.getFirstName() + " " + employee.getLastName(), employee.getEmail(),
                null, UserRole.EMPLOYEE);
        account.setEmployeeId(employee.getId());
        account.requireActivation(token.hash(), token.expiresAt());
        users.save(account);
        String exposedToken = tokenDelivery.deliver(account.getEmail(), account.getDisplayName(), token.value(),
                token.expiresAt(), AccountTokenDelivery.Purpose.PASSWORD_SETUP);
        return new AccountInvitationResponse(new AuthResponse.UserView(account.getId(), companyId, employee.getId(),
                account.getEmail(), account.getRole()), exposedToken, exposedToken == null ? null : token.expiresAt());
    }

    @Transactional
    public AccountInvitationResponse createCompanyAccount(UUID companyId, CurrentUser actor,
                                                           CreateCompanyAccountRequest request) {
        requireCompanyRole(companyId, actor, UserRole.COMPANY_ADMIN);
        if (request.role() != UserRole.HR && request.role() != UserRole.MANAGER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only HR and MANAGER accounts can be created here");
        }
        String email = request.email();
        java.util.UUID employeeId = null;
        Employee linkedEmployee = null;
        if (request.role() == UserRole.MANAGER) {
            if (request.employeeId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "A manager account must be linked to an employee");
            }
            linkedEmployee = employees.findForUpdateByIdAndCompanyId(request.employeeId(), companyId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
            if (linkedEmployee.getStatus() != com.workmark.employee.entity.EmployeeStatus.ACTIVE) {
                throw new ApiException(HttpStatus.CONFLICT, "Cannot create an account for an inactive employee");
            }
            if (request.email() != null && !request.email().equalsIgnoreCase(linkedEmployee.getEmail())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Manager account email must match the employee record");
            }
            email = linkedEmployee.getEmail();
            employeeId = linkedEmployee.getId();
        } else if (request.employeeId() != null || email == null || email.isBlank()
                || request.displayName() == null || request.displayName().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "HR accounts require a name and email and no employeeId");
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "An account already uses this email");
        }
        String displayName = linkedEmployee != null
                ? linkedEmployee.getFirstName() + " " + linkedEmployee.getLastName()
                : request.displayName();
        ActivationTokenService.IssuedToken token = activationTokens.issue(passwordResetTtl);
        UserAccount account = new UserAccount(companyId, displayName, email, null, request.role());
        account.setEmployeeId(employeeId);
        account.requireActivation(token.hash(), token.expiresAt());
        users.save(account);
        String exposedToken = tokenDelivery.deliver(account.getEmail(), account.getDisplayName(), token.value(),
                token.expiresAt(), AccountTokenDelivery.Purpose.PASSWORD_SETUP);
        return new AccountInvitationResponse(new AuthResponse.UserView(account.getId(), companyId, employeeId,
                account.getEmail(), account.getRole()), exposedToken, exposedToken == null ? null : token.expiresAt());
    }

    @Transactional
    public void changePassword(CurrentUser currentUser, ChangePasswordRequest request) {
        passwordPolicy.validate(request.newPassword());
        UserAccount user = users.findById(currentUser.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New password must be different");
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()));
    }

    @Transactional
    public PasswordResetRequestResponse requestPasswordReset(String email, String remoteAddress) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!passwordResetRateLimiter.isAllowed(remoteAddress, normalizedEmail)) {
            return new PasswordResetRequestResponse(null);
        }
        passwordResetRateLimiter.recordRequest(remoteAddress, normalizedEmail);
        UserAccount user = users.findForUpdateByEmailIgnoreCase(normalizedEmail)
                .filter(UserAccount::isActive)
                .orElse(null);
        if (user == null) {
            return new PasswordResetRequestResponse(null);
        }
        Instant now = clock.instant();
        if (user.getPasswordResetRequestedAt() != null
                && now.isBefore(user.getPasswordResetRequestedAt().plus(passwordResetCooldown))) {
            return new PasswordResetRequestResponse(null);
        }
        if (user.getPasswordResetTokenHash() != null
                && user.getPasswordResetExpiresAt() != null
                && now.isBefore(user.getPasswordResetExpiresAt())) {
            return new PasswordResetRequestResponse(null);
        }
        ActivationTokenService.IssuedToken token = activationTokens.issue();
        user.requirePasswordReset(token.hash(), token.expiresAt(), now);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            if (tokenDelivery.exposesDevelopmentToken()) {
                String exposedToken = tokenDelivery.deliver(user.getEmail(), user.getDisplayName(), token.value(),
                        token.expiresAt(), AccountTokenDelivery.Purpose.PASSWORD_RESET);
                return new PasswordResetRequestResponse(exposedToken);
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        tokenDelivery.deliver(user.getEmail(), user.getDisplayName(), token.value(),
                                token.expiresAt(), AccountTokenDelivery.Purpose.PASSWORD_RESET);
                    } catch (RuntimeException exception) {
                        log.error("Password-reset delivery failed for account {}", user.getId());
                        clearPasswordResetToken(token.hash());
                    }
                }
            });
            return new PasswordResetRequestResponse(
                    tokenDelivery.exposesDevelopmentToken() ? token.value() : null);
        }
        String exposedToken = tokenDelivery.deliver(user.getEmail(), user.getDisplayName(), token.value(),
                token.expiresAt(), AccountTokenDelivery.Purpose.PASSWORD_RESET);
        return new PasswordResetRequestResponse(exposedToken);
    }

    @Transactional
    public void resetPassword(PasswordResetConfirmation request) {
        passwordPolicy.validate(request.newPassword());
        String tokenHash = activationTokens.hash(request.token());
        UserAccount user = users.findForPasswordResetByTokenHash(tokenHash)
                .filter(account -> account.getPasswordResetExpiresAt().isAfter(clock.instant()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Password reset token is invalid or expired"));
        user.resetPassword(passwordEncoder.encode(request.newPassword()));
        passwordResetRateLimiter.recordSuccess(user.getEmail());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void clearPasswordResetToken(String tokenHash) {
        users.findForPasswordResetByTokenHash(tokenHash).ifPresent(user -> {
            user.resetPasswordToken();
        });
    }

    private void requireCompanyRole(UUID companyId, CurrentUser actor, UserRole... allowedRoles) {
        if (actor.getCompanyId() == null || !companyId.equals(actor.getCompanyId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This operation is not available for this company");
        }
        for (UserRole role : allowedRoles) {
            if (actor.getRole() == role) return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "You are not authorized to create accounts");
    }

    private AuthResponse response(CurrentUser user) {
        return new AuthResponse(jwtService.issue(user), "Bearer", expirationSeconds, view(user));
    }

    private AuthResponse.UserView view(CurrentUser user) {
        return new AuthResponse.UserView(user.getId(), user.getCompanyId(), user.getEmployeeId(),
                user.getUsername(), user.getRole());
    }
}
