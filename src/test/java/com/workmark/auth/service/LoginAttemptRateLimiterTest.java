package com.workmark.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptRateLimiterTest {
    @Test
    void throttlesNormalizedAccountAcrossIpAddressesAndProgressivelyReleases() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-22T12:00:00Z"));
        LoginAttemptRateLimiter limiter = limiter(clock);

        assertTrue(limiter.isAllowed("192.0.2.1", " User@example.com "));
        limiter.recordFailure("192.0.2.1", " User@example.com ");
        assertTrue(limiter.isAllowed("192.0.2.2", "user@example.com"));
        limiter.recordFailure("192.0.2.2", "user@example.com");
        assertFalse(limiter.isAllowed("192.0.2.3", "USER@example.com"));

        clock.advance(Duration.ofSeconds(30));
        assertTrue(limiter.isAllowed("192.0.2.3", "user@example.com"));
        limiter.recordFailure("192.0.2.3", "user@example.com");
        limiter.recordFailure("192.0.2.3", "user@example.com");
        assertFalse(limiter.isAllowed("192.0.2.4", "user@example.com"));

        clock.advance(Duration.ofSeconds(60));
        assertTrue(limiter.isAllowed("192.0.2.4", "user@example.com"));
    }

    @Test
    void throttlesAnIpAcrossDifferentAccountIdentifiers() {
        LoginAttemptRateLimiter limiter = limiter(new MutableClock(Instant.parse("2026-11-22T12:00:00Z")));

        assertTrue(limiter.isAllowed("198.51.100.1", "first@example.com"));
        limiter.recordFailure("198.51.100.1", "first@example.com");
        assertTrue(limiter.isAllowed("198.51.100.1", "second@example.com"));
        limiter.recordFailure("198.51.100.1", "second@example.com");

        assertFalse(limiter.isAllowed("198.51.100.1", "third@example.com"));
        assertTrue(limiter.isAllowed("198.51.100.2", "third@example.com"));
    }

    @Test
    void successfulLoginClearsOnlyTheAccountFailureState() {
        LoginAttemptRateLimiter limiter = limiter(new MutableClock(Instant.parse("2026-11-22T12:00:00Z")));
        String ip = "203.0.113.1";

        limiter.isAllowed(ip, "user@example.com");
        limiter.recordFailure(ip, "user@example.com");
        limiter.recordSuccess("USER@example.com");

        assertTrue(limiter.isAllowed(ip, "user@example.com"));
        limiter.recordFailure(ip, "user@example.com");
        limiter.recordFailure(ip, "another@example.com");
        assertFalse(limiter.isAllowed(ip, "third@example.com"));
    }

    @Test
    void failsClosedWhenTrackedKeyCapacityIsReached() {
        LoginRateLimitProperties properties = properties();
        properties.setMaxTrackedKeys(2);
        LoginAttemptRateLimiter limiter = new LoginAttemptRateLimiter(properties,
                new MutableClock(Instant.parse("2026-11-22T12:00:00Z")));

        assertTrue(limiter.isAllowed("192.0.2.1", "first@example.com"));
        assertFalse(limiter.isAllowed("192.0.2.1", "second@example.com"));
    }

    private LoginAttemptRateLimiter limiter(Clock clock) {
        return new LoginAttemptRateLimiter(properties(), clock);
    }

    private LoginRateLimitProperties properties() {
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setMaxFailures(2);
        properties.setWindow(Duration.ofMinutes(5));
        properties.setBaseBlock(Duration.ofSeconds(30));
        properties.setMaxBlock(Duration.ofMinutes(2));
        properties.setMaxTrackedKeys(20);
        return properties;
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
