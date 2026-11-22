package com.workmark.auth.service;

import com.workmark.auth.entity.UserAccount;
import com.workmark.auth.repository.UserAccountRepository;
import com.workmark.employee.entity.EmployeeStatus;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.security.CurrentUser;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CustomUserDetailsService implements UserDetailsService {
    private final UserAccountRepository users;
    private final EmployeeRepository employees;

    public CustomUserDetailsService(UserAccountRepository users, EmployeeRepository employees) {
        this.users = users;
        this.employees = employees;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        UserAccount user = users.findByEmailIgnoreCase(email)
                .filter(UserAccount::isActive)
                .filter(this::hasActiveEmployeeLink)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return toCurrentUser(user);
    }

    @Transactional(readOnly = true)
    public CurrentUser loadById(UUID userId) throws UsernameNotFoundException {
        UserAccount user = users.findById(userId)
                .filter(UserAccount::isActive)
                .filter(this::hasActiveEmployeeLink)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return toCurrentUser(user);
    }

    private CurrentUser toCurrentUser(UserAccount user) {
        return new CurrentUser(user.getId(), user.getCompanyId(), user.getEmployeeId(), user.getEmail(),
                user.getPasswordHash(), user.getRole(), user.getTokenVersion());
    }

    private boolean hasActiveEmployeeLink(UserAccount user) {
        if (user.getRole() != com.workmark.auth.entity.UserRole.EMPLOYEE
                && user.getRole() != com.workmark.auth.entity.UserRole.MANAGER) {
            return true;
        }
        if (user.getCompanyId() == null || user.getEmployeeId() == null) {
            return false;
        }
        return employees.findByIdAndCompanyId(user.getEmployeeId(), user.getCompanyId())
                .map(employee -> employee.getStatus() == EmployeeStatus.ACTIVE)
                .orElse(false);
    }
}
