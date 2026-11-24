package com.workmark.auth.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
@Profile({"dev", "test"})
public class InMemoryPasswordResetRateLimiter implements PasswordResetRateLimiter {
    private final LoginRateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private Instant nextCleanup = Instant.EPOCH;

    public InMemoryPasswordResetRateLimiter(LoginRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        properties.validate();
    }

    @Override
    public synchronized boolean isAllowed(String remoteAddress, String email) {
        Instant now = clock.instant();
        if (!now.isBefore(nextCleanup)) {
            removeExpired(now);
            nextCleanup = now.plus(java.time.Duration.ofMinutes(1));
        }
        String ipKey = ipKey(remoteAddress);
        String accountKey = accountKey(email);
        int newKeys = (buckets.containsKey(ipKey) ? 0 : 1)
                + (buckets.containsKey(accountKey) ? 0 : 1);
        if (buckets.size() + newKeys > properties.getResetMaxTrackedKeys()) {
            return false;
        }
        return !blocked(ipKey(remoteAddress), now) && !blocked(accountKey(email), now);
    }

    @Override
    public synchronized void recordRequest(String remoteAddress, String email) {
        Instant now = clock.instant();
        record(ipKey(remoteAddress), properties.getResetIpMaxFailures(), now);
        record(accountKey(email), properties.getResetMaxFailures(), now);
    }

    @Override
    public synchronized void recordSuccess(String email) {
        buckets.remove(accountKey(email));
    }

    private void record(String key, int threshold, Instant now) {
        if (!buckets.containsKey(key) && buckets.size() >= properties.getResetMaxTrackedKeys()) {
            return;
        }
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket());
        if (bucket.blockedUntil != null && now.isBefore(bucket.blockedUntil)) return;
        if (bucket.blockedUntil != null) bucket.blockedUntil = null;
        bucket.failures++;
        bucket.lastTouched = now;
        if (bucket.failures >= threshold) {
            bucket.blockedUntil = now.plus(properties.getBaseBlock());
            bucket.blockLevel++;
            bucket.failures = 0;
        }
    }

    private boolean blocked(String key, Instant now) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) return false;
        if (bucket.blockedUntil != null && now.isBefore(bucket.blockedUntil)) return true;
        if (bucket.blockedUntil != null) bucket.blockedUntil = null;
        return false;
    }

    private void removeExpired(Instant now) {
        java.util.Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Bucket bucket = iterator.next().getValue();
            if (bucket.blockedUntil != null && now.isBefore(bucket.blockedUntil)) continue;
            if (bucket.lastTouched.plus(properties.getWindow()).isBefore(now)) iterator.remove();
        }
    }

    private String ipKey(String address) {
        return fingerprint("reset-ip:" + (address == null ? "unknown" : address));
    }

    private String accountKey(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return fingerprint("reset-account:" + normalized);
    }

    private String fingerprint(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class Bucket {
        private int failures;
        private int blockLevel;
        private Instant blockedUntil;
        private Instant lastTouched = Instant.EPOCH;
    }
}
