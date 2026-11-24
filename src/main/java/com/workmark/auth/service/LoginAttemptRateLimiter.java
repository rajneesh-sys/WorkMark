package com.workmark.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

@Service
@Profile({"dev", "test"})
public class LoginAttemptRateLimiter implements LoginRateLimiter {
    private final LoginRateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private Instant nextCleanup = Instant.EPOCH;

    public LoginAttemptRateLimiter(LoginRateLimitProperties properties, Clock clock) {
        properties.validate();
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized boolean isAllowed(String remoteAddress, String email) {
        Instant now = clock.instant();
        if (!now.isBefore(nextCleanup)) {
            removeExpired(now);
            nextCleanup = now.plus(Duration.ofMinutes(1));
        }
        String ipKey = ipKey(remoteAddress);
        String accountKey = accountKey(email);
        Bucket ipBucket = buckets.get(ipKey);
        Bucket accountBucket = buckets.get(accountKey);
        refresh(ipBucket, now);
        refresh(accountBucket, now);

        if (blocked(ipBucket, now) || blocked(accountBucket, now)) {
            return false;
        }
        int newKeys = (ipBucket == null ? 1 : 0) + (accountBucket == null ? 1 : 0);
        if (buckets.size() + newKeys > properties.getMaxTrackedKeys()) {
            return false;
        }
        buckets.computeIfAbsent(ipKey, ignored -> new Bucket()).lastTouched = now;
        buckets.computeIfAbsent(accountKey, ignored -> new Bucket()).lastTouched = now;
        return true;
    }

    public synchronized void recordFailure(String remoteAddress, String email) {
        Instant now = clock.instant();
        recordFailure(ipKey(remoteAddress), properties.getIpMaxFailures(), now);
        recordFailure(accountKey(email), properties.getMaxFailures(), now);
    }

    public synchronized void recordSuccess(String email) {
        buckets.remove(accountKey(email));
    }

    private void recordFailure(String key, int threshold, Instant now) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return;
        }
        refresh(bucket, now);
        bucket.failures.addLast(now);
        bucket.lastTouched = now;
        if (bucket.failures.size() >= threshold) {
            bucket.blockedUntil = now.plus(blockDuration(bucket.blockLevel));
            bucket.blockLevel++;
            bucket.failures.clear();
        }
    }

    private void refresh(Bucket bucket, Instant now) {
        if (bucket == null) {
            return;
        }
        if (bucket.blockedUntil != null && !now.isBefore(bucket.blockedUntil)) {
            bucket.blockedUntil = null;
            bucket.failures.clear();
        }
        Instant cutoff = now.minus(properties.getWindow());
        while (!bucket.failures.isEmpty() && bucket.failures.peekFirst().isBefore(cutoff)) {
            bucket.failures.removeFirst();
        }
    }

    private boolean blocked(Bucket bucket, Instant now) {
        return bucket != null && bucket.blockedUntil != null && now.isBefore(bucket.blockedUntil);
    }

    private Duration blockDuration(int level) {
        long multiplier = 1L << Math.min(level, 30);
        try {
            Duration duration = properties.getBaseBlock().multipliedBy(multiplier);
            return duration.compareTo(properties.getMaxBlock()) > 0 ? properties.getMaxBlock() : duration;
        } catch (ArithmeticException exception) {
            return properties.getMaxBlock();
        }
    }

    private void removeExpired(Instant now) {
        Instant cutoff = now.minus(properties.getWindow());
        Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Bucket bucket = iterator.next().getValue();
            refresh(bucket, now);
            if (bucket.failures.isEmpty() && bucket.blockedUntil == null
                    && bucket.lastTouched.isBefore(cutoff)) {
                iterator.remove();
            }
        }
    }

    private String ipKey(String remoteAddress) {
        String address = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress.trim();
        return "ip:" + fingerprint(address);
    }

    private String accountKey(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return "account:" + fingerprint(normalized);
    }

    private String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class Bucket {
        private final ArrayDeque<Instant> failures = new ArrayDeque<>();
        private Instant blockedUntil;
        private Instant lastTouched = Instant.EPOCH;
        private int blockLevel;
    }
}
