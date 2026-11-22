package com.workmark.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.*;

class SecurityConfigurationTest {
    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void corsUsesExactConfiguredOriginsWithoutCredentials() {
        var source = configuration.corsConfigurationSource("https://workmark.example, https://admin.example");
        CorsConfiguration cors = source.getCorsConfiguration(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/employees"));

        assertEquals(java.util.List.of("https://workmark.example", "https://admin.example"),
                cors.getAllowedOrigins());
        assertFalse(cors.getAllowCredentials());
        assertTrue(cors.getAllowedMethods().contains("OPTIONS"));
    }

    @Test
    void corsRejectsWildcardOrigins() {
        assertThrows(IllegalStateException.class, () -> configuration.corsConfigurationSource("*"));
    }
}
