package com.workmark.security;

import com.workmark.auth.entity.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class CurrentUser implements UserDetails {
    private final UUID id;
    private final UUID companyId;
    private final UUID employeeId;
    private final String email;
    private final String password;
    private final UserRole role;
    private final int tokenVersion;

    public CurrentUser(UUID id, UUID companyId, UUID employeeId, String email, String password,
                       UserRole role, int tokenVersion) {
        this.id = id;
        this.companyId = companyId;
        this.employeeId = employeeId;
        this.email = email;
        this.password = password;
        this.role = role;
        this.tokenVersion = tokenVersion;
    }
    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getEmployeeId() { return employeeId; }
    public UserRole getRole() { return role; }
    public int getTokenVersion() { return tokenVersion; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
