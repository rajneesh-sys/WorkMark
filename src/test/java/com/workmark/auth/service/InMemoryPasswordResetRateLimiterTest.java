package com.workmark.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPasswordResetRateLimiterTest {
    @Test
    void appliesProgressiveAccountLockoutAndResetsFailureWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-22T12:00:00Z"));
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setResetMaxFailures(2);
        properties.setResetIpMaxFailures(20);
        properties.setBaseBlock(Duration.ofSeconds(10));
        properties.setMaxBlock(Duration.ofSeconds(40));
        properties.setWindow(Duration.ofMinutes(5));
        InMemoryPasswordResetRateLimiter limiter = new InMemoryPasswordResetRateLimiter(properties, clock);

        limiter.recordRequest("192.0.2.1", "account@example.com");
        limiter.recordRequest("192.0.2.2", "account@example.com");
        assertFalse(limiter.isAllowed("192.0.2.3", "account@example.com"));

        clock.advance(Duration.ofSeconds(10));
        assertTrue(limiter.isAllowed("192.0.2.3", "account@example.com"));
        limiter.recordRequest("192.0.2.3", "account@example.com");
        limiter.recordRequest("192.0.2.4", "account@example.com");
        assertFalse(limiter.isAllowed("192.0.2.5", "account@example.com"));

        clock.advance(Duration.ofSeconds(19));
        assertFalse(limiter.isAllowed("192.0.2.5", "account@example.com"));
        clock.advance(Duration.ofSeconds(1));
        assertTrue(limiter.isAllowed("192.0.2.5", "account@example.com"));
    }

    @Test
    void honorsIndependentIpAndAccountThresholds() {
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setResetMaxFailures(3);
        properties.setResetIpMaxFailures(2);
        properties.setResetMaxTrackedKeys(20);
        InMemoryPasswordResetRateLimiter limiter = new InMemoryPasswordResetRateLimiter(properties,
                new MutableClock(Instant.parse("2026-11-22T12:00:00Z")));

        limiter.recordRequest("192.0.2.1", "first@example.com");
        assertTrue(limiter.isAllowed("192.0.2.1", "second@example.com"));
        limiter.recordRequest("192.0.2.1", "second@example.com");

        assertFalse(limiter.isAllowed("192.0.2.1", "third@example.com"));
        assertTrue(limiter.isAllowed("192.0.2.2", "third@example.com"));
    }

    @Test
    void concurrentRequestsPreserveAccountFailureThreshold() throws Exception {
        int workers = 12;
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setResetMaxFailures(workers);
        properties.setResetIpMaxFailures(100);
        properties.setResetMaxTrackedKeys(100);
        InMemoryPasswordResetRateLimiter limiter = new InMemoryPasswordResetRateLimiter(properties,
                new MutableClock(Instant.parse("2026-11-22T12:00:00Z")));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        var tasks = new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 0; i < workers; i++) {
                final int index = i;
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    limiter.recordRequest("192.0.2." + (index + 1), "same@example.com");
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertFalse(limiter.isAllowed("192.0.2.100", "same@example.com"));
    }

    private static final class MutableClock extends Clock {
        private Instant current;
        private MutableClock(Instant current) { this.current = current; }
        private void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
