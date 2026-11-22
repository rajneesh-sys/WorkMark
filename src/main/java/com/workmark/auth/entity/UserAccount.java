package com.workmark.auth.entity;

import com.workmark.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;
import java.util.Locale;

@Entity
@Table(name = "user_accounts")
public class UserAccount extends AuditedEntity {
    @Id
    private UUID id;
    @Column(name = "company_id")
    private UUID companyId;
    @Column(name = "employee_id")
    private UUID employeeId;
    @Column(name = "display_name", length = 160)
    private String displayName;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(name = "password_hash", length = 100)
    private String passwordHash;
    @Column(name = "activation_token_hash", length = 64)
    private String activationTokenHash;
    @Column(name = "activation_expires_at")
    private Instant activationExpiresAt;
    @Column(name = "password_reset_token_hash", length = 64)
    private String passwordResetTokenHash;
    @Column(name = "password_reset_expires_at")
    private Instant passwordResetExpiresAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole role;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    protected UserAccount() {}
    public UserAccount(UUID companyId, String email, String passwordHash, UserRole role) {
        this(companyId, null, email, passwordHash, role);
    }

    public UserAccount(UUID companyId, String displayName, String email, String passwordHash, UserRole role) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.displayName = displayName;
        this.email = email.toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = true;
    }
    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getEmployeeId() { return employeeId; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getActivationTokenHash() { return activationTokenHash; }
    public Instant getActivationExpiresAt() { return activationExpiresAt; }
    public String getPasswordResetTokenHash() { return passwordResetTokenHash; }
    public Instant getPasswordResetExpiresAt() { return passwordResetExpiresAt; }
    public UserRole getRole() { return role; }
    public boolean isActive() { return active; }
    public int getTokenVersion() { return tokenVersion; }
    public void invalidateTokens() { tokenVersion++; }
    public void deactivate() {
        if (active) {
            active = false;
            invalidateTokens();
        }
    }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public void requireActivation(String tokenHash, Instant expiresAt) {
        passwordHash = null;
        active = false;
        activationTokenHash = tokenHash;
        activationExpiresAt = expiresAt;
    }
    public void activate(String encodedPassword) {
        passwordHash = encodedPassword;
        active = true;
        activationTokenHash = null;
        activationExpiresAt = null;
    }
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        invalidateTokens();
    }
    public void requirePasswordReset(String tokenHash, Instant expiresAt) {
        passwordResetTokenHash = tokenHash;
        passwordResetExpiresAt = expiresAt;
    }
    public void resetPassword(String encodedPassword) {
        changePassword(encodedPassword);
        passwordResetTokenHash = null;
        passwordResetExpiresAt = null;
    }
}
