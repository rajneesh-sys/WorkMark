package com.workmark.auth.bootstrap;

import com.workmark.auth.entity.UserAccount;
import com.workmark.auth.entity.UserRole;
import com.workmark.auth.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SuperAdminBootstrapServiceTest {
    @Mock PostgresBootstrapLock bootstrapLock;
    @Mock UserAccountRepository users;
    @Mock PasswordEncoder passwordEncoder;

    @Test
    void disabledBootstrapDoesNotCreateAnAccountOrTakeTheLock() {
        SuperAdminBootstrapService service = service(new SuperAdminBootstrapProperties());

        service.bootstrapIfEnabled();

        verifyNoInteractions(bootstrapLock, users, passwordEncoder);
    }

    @Test
    void enabledBootstrapRequiresAllConfigurationWithoutEchoingSecrets() {
        SuperAdminBootstrapProperties properties = new SuperAdminBootstrapProperties();
        properties.setEnabled(true);
        properties.setPassword("SensitiveConfiguredPassword");
        SuperAdminBootstrapService service = service(properties);

        IllegalStateException error = assertThrows(IllegalStateException.class, service::bootstrapIfEnabled);

        assertFalse(error.getMessage().contains("SensitiveConfiguredPassword"));
        verifyNoInteractions(bootstrapLock, users, passwordEncoder);
    }

    @Test
    void createsExactlyOnePlatformAdminWithEncodedPasswordAndNoCompany() {
        SuperAdminBootstrapProperties properties = configuredProperties();
        when(users.existsByRole(UserRole.SUPER_ADMIN)).thenReturn(false);
        when(passwordEncoder.encode("VerySecureBootstrapPassword123"))
                .thenReturn("$2a$encoded-value");
        when(users.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SuperAdminBootstrapService service = service(properties);

        service.bootstrapIfEnabled();

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(captor.capture());
        UserAccount saved = captor.getValue();
        assertEquals(UserRole.SUPER_ADMIN, saved.getRole());
        assertNull(saved.getCompanyId());
        assertEquals("Platform Owner", saved.getDisplayName());
        assertEquals("root@example.com", saved.getEmail());
        assertEquals("$2a$encoded-value", saved.getPasswordHash());
        assertTrue(saved.isActive());
        verify(bootstrapLock).acquire();
    }

    @Test
    void existingSuperAdminIsNeverReplacedOrDuplicated() {
        SuperAdminBootstrapProperties properties = configuredProperties();
        when(users.existsByRole(UserRole.SUPER_ADMIN)).thenReturn(true);
        SuperAdminBootstrapService service = service(properties);

        service.bootstrapIfEnabled();

        verify(users, never()).save(any());
        verify(passwordEncoder, never()).encode(any(CharSequence.class));
    }

    private SuperAdminBootstrapService service(SuperAdminBootstrapProperties properties) {
        return new SuperAdminBootstrapService(properties, bootstrapLock, users, passwordEncoder);
    }

    private SuperAdminBootstrapProperties configuredProperties() {
        SuperAdminBootstrapProperties properties = new SuperAdminBootstrapProperties();
        properties.setEnabled(true);
        properties.setName("Platform Owner");
        properties.setEmail("root@example.com");
        properties.setPassword("VerySecureBootstrapPassword123");
        return properties;
    }
}
