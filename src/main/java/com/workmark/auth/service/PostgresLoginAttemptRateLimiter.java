package com.workmark.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Profile("prod")
public class PostgresLoginAttemptRateLimiter implements LoginRateLimiter {
    private static final long INSERT_LOCK_KEY = 6288528922353146194L;
    private static final String SELECT_BUCKET =
            "select failure_count, window_started_at, blocked_until, block_level, last_touched " +
                    "from login_attempt_buckets where key_hash = ?";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LoginRateLimitProperties properties;
    private final Clock clock;
    private final byte[] hmacKey;
    private final AtomicReference<Instant> nextCleanup = new AtomicReference<>(Instant.EPOCH);

    public PostgresLoginAttemptRateLimiter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                           LoginRateLimitProperties properties, Clock clock,
                                           @Value("${app.jwt.secret}") String keyMaterial) {
        properties.validate();
        if (keyMaterial.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret must contain at least 32 bytes");
        }
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
        this.clock = clock;
        this.hmacKey = keyMaterial.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean isAllowed(String remoteAddress, String email) {
        Instant now = clock.instant();
        cleanupIfDue(now);
        List<String> keys = keys(remoteAddress, email);
        Map<String, Bucket> buckets = load(keys, false);
        if (buckets.values().stream().anyMatch(bucket -> bucket.blockedUntil != null
                && now.isBefore(bucket.blockedUntil))) {
            return false;
        }
        if (buckets.size() < keys.size()) {
            Integer count = jdbc.queryForObject("select count(*) from login_attempt_buckets", Integer.class);
            return count != null && count < properties.getMaxTrackedKeys();
        }
        return true;
    }

    @Override
    public void recordFailure(String remoteAddress, String email) {
        Instant now = clock.instant();
        List<BucketKey> keys = List.of(
                new BucketKey(keyHash("ip:" + normalizedAddress(remoteAddress)), properties.getIpMaxFailures()),
                new BucketKey(keyHash("account:" + normalizeEmail(email)), properties.getMaxFailures()));
        List<BucketKey> ordered = keys.stream().sorted(Comparator.comparing(BucketKey::hash)).toList();
        transactions.executeWithoutResult(status -> {
            for (BucketKey key : ordered) {
                Optional<Bucket> stored = loadOne(key.hash, true);
                if (stored.isEmpty()) {
                    if (!insertBucket(key.hash, now)) {
                        continue;
                    }
                    stored = loadOne(key.hash, true);
                }
                Bucket bucket = stored.orElseThrow();
                Instant windowStart = bucket.windowStartedAt;
                int failures = bucket.failureCount;
                if (!now.isBefore(windowStart.plus(properties.getWindow()))) {
                    windowStart = now;
                    failures = 0;
                }
                failures++;
                Instant blockedUntil = null;
                int blockLevel = bucket.blockLevel;
                if (failures >= key.maxFailures) {
                    blockedUntil = now.plus(blockDuration(blockLevel));
                    blockLevel++;
                    failures = 0;
                    windowStart = now;
                }
                jdbc.update("""
                        update login_attempt_buckets
                        set failure_count = ?, window_started_at = ?, blocked_until = ?,
                            block_level = ?, last_touched = ?
                        where key_hash = ?
                        """, failures, Timestamp.from(windowStart),
                        blockedUntil == null ? null : Timestamp.from(blockedUntil),
                        blockLevel, Timestamp.from(now), key.hash);
            }
        });
    }

    @Override
    public void recordSuccess(String email) {
        String accountKey = keyHash("account:" + normalizeEmail(email));
        transactions.executeWithoutResult(status ->
                jdbc.update("delete from login_attempt_buckets where key_hash = ?", accountKey));
    }

    private boolean insertBucket(String keyHash, Instant now) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
                statement.setLong(1, INSERT_LOCK_KEY);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("Failed to serialize rate-limit key creation");
                }
            }
            return null;
        });
        jdbc.update("""
                delete from login_attempt_buckets
                where last_touched < ? and (blocked_until is null or blocked_until <= ?)
                """, Timestamp.from(now.minus(properties.getWindow().plus(properties.getMaxBlock()))),
                Timestamp.from(now));
        Optional<Bucket> existing = loadOne(keyHash, true);
        if (existing.isPresent()) {
            return true;
        }
        Integer count = jdbc.queryForObject("select count(*) from login_attempt_buckets", Integer.class);
        if (count == null || count >= properties.getMaxTrackedKeys()) {
            return false;
        }
        jdbc.update("""
                insert into login_attempt_buckets
                    (key_hash, failure_count, window_started_at, blocked_until, block_level, last_touched)
                values (?, 0, ?, null, 0, ?)
                on conflict (key_hash) do nothing
                """, keyHash, Timestamp.from(now), Timestamp.from(now));
        return true;
    }

    private Map<String, Bucket> load(List<String> keys, boolean lock) {
        Map<String, Bucket> found = new java.util.HashMap<>();
        for (String key : keys) {
            loadOne(key, lock).ifPresent(bucket -> found.put(key, bucket));
        }
        return found;
    }

    private Optional<Bucket> loadOne(String key, boolean lock) {
        String sql = SELECT_BUCKET + (lock ? " for update" : "");
        List<Bucket> rows = jdbc.query(sql, (result, row) -> new Bucket(
                result.getInt("failure_count"),
                result.getTimestamp("window_started_at").toInstant(),
                result.getTimestamp("blocked_until") == null
                        ? null : result.getTimestamp("blocked_until").toInstant(),
                result.getInt("block_level"),
                result.getTimestamp("last_touched").toInstant()), key);
        return rows.stream().findFirst();
    }

    private void cleanupIfDue(Instant now) {
        while (true) {
            Instant due = nextCleanup.get();
            if (now.isBefore(due)) return;
            if (nextCleanup.compareAndSet(due, now.plus(Duration.ofMinutes(1)))) {
                transactions.executeWithoutResult(status -> jdbc.update("""
                            delete from login_attempt_buckets
                            where last_touched < ? and (blocked_until is null or blocked_until <= ?)
                            """, Timestamp.from(now.minus(properties.getWindow().plus(properties.getMaxBlock()))),
                        Timestamp.from(now)));
                return;
            }
        }
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

    private List<String> keys(String remoteAddress, String email) {
        List<String> keys = new ArrayList<>(2);
        keys.add(keyHash("ip:" + normalizedAddress(remoteAddress)));
        keys.add(keyHash("account:" + normalizeEmail(email)));
        return keys;
    }

    private String normalizedAddress(String address) {
        return address == null || address.isBlank() ? "unknown" : address.trim();
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String keyHash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    private record BucketKey(String hash, int maxFailures) {}

    private record Bucket(int failureCount, Instant windowStartedAt, Instant blockedUntil,
                          int blockLevel, Instant lastTouched) {}
}
