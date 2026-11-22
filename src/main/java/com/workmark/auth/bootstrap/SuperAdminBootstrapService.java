package com.workmark.auth.bootstrap;

import com.workmark.auth.entity.UserAccount;
import com.workmark.auth.entity.UserRole;
import com.workmark.auth.repository.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SuperAdminBootstrapService {
    private final SuperAdminBootstrapProperties properties;
    private final PostgresBootstrapLock bootstrapLock;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;

    public SuperAdminBootstrapService(SuperAdminBootstrapProperties properties,
                                      PostgresBootstrapLock bootstrapLock,
                                      UserAccountRepository users,
                                      PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.bootstrapLock = bootstrapLock;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void bootstrapIfEnabled() {
        if (!properties.isEnabled()) return;
        properties.validateWhenEnabled();
        bootstrapLock.acquire();
        if (users.existsByRole(UserRole.SUPER_ADMIN)) return;
        if (users.existsByEmailIgnoreCase(properties.getEmail().trim())) {
            throw new IllegalStateException("Configured Super Admin email is already in use");
        }

        UserAccount superAdmin = new UserAccount(null, properties.getName().trim(),
                properties.getEmail().trim(), passwordEncoder.encode(properties.getPassword()),
                UserRole.SUPER_ADMIN);
        users.save(superAdmin);
    }
}
