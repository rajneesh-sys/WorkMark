package com.workmark.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ActivationTokenServiceTest {
    @Test
    void issuesUniqueUrlSafeTokenAndStoresOnlyItsHash() {
        Instant now = Instant.parse("2026-11-22T12:00:00Z");
        ActivationTokenService service = new ActivationTokenService(
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofHours(2));

        var first = service.issue();
        var second = service.issue();

        assertEquals(43, first.value().length());
        assertNotEquals(first.value(), first.hash());
        assertNotEquals(first.value(), second.value());
        assertEquals(now.plus(Duration.ofHours(2)), first.expiresAt());
        assertEquals(first.hash(), service.hash(first.value()));
    }

    @Test
    void rejectsNonPositiveTokenLifetime() {
        assertThrows(IllegalArgumentException.class, () -> new ActivationTokenService(
                Clock.systemUTC(), Duration.ZERO));
    }
}
