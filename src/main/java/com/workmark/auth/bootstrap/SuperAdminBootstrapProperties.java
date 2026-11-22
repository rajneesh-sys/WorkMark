package com.workmark.auth.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

@ConfigurationProperties(prefix = "app.bootstrap.super-admin")
public class SuperAdminBootstrapProperties {
    private boolean enabled;
    private String name;
    private String email;
    private String password;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public void validateWhenEnabled() {
        boolean validName = name != null && !name.isBlank() && name.trim().length() <= 160;
        String normalizedEmail = email == null ? "" : email.trim();
        boolean validEmail = normalizedEmail.length() <= 254
                && normalizedEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
        int passwordBytes = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (!validName || !validEmail || password == null || password.length() < 12 || passwordBytes > 72) {
            throw new IllegalStateException(
                    "Super Admin bootstrap is enabled but required configuration is missing or invalid");
        }
    }
}
