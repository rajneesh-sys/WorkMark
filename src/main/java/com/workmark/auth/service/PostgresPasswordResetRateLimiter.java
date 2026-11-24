package com.workmark.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

@Service
@Profile({"prod", "staging"})
public class PostgresPasswordResetRateLimiter implements PasswordResetRateLimiter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LoginRateLimitProperties properties;
    private final Clock clock;
    private final byte[] hmacKey;
    private Instant nextCleanup = Instant.EPOCH;

    public PostgresPasswordResetRateLimiter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                            LoginRateLimitProperties properties, Clock clock,
                                            @Value("${app.jwt.secret}") String keyMaterial) {
        properties.validate();
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
        this.hmacKey = keyMaterial.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean isAllowed(String remoteAddress, String email) {
        cleanupExpired();
        Instant now = clock.instant();
        String[] keys = {keyHash("reset-ip:" + remoteAddress),
                keyHash("reset-account:" + normalize(email))};
        int missingKeys = 0;
        for (String key : keys) {
            java.util.List<Instant> blockedUntil = jdbc.query(
                    "select blocked_until from password_reset_attempt_buckets where key_hash = ?",
                    (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), key);
            if (blockedUntil.isEmpty()) {
                missingKeys++;
                continue;
            }
            if (blockedUntil.get(0) != null && now.isBefore(blockedUntil.get(0))) return false;
        }
        if (missingKeys > 0) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from password_reset_attempt_buckets", Integer.class);
            if (count != null && count + missingKeys > properties.getResetMaxTrackedKeys()) return false;
        }
        return true;
    }

    @Override
    public void recordRequest(String remoteAddress, String email) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            cleanupExpired();
            recordKey(keyHash("reset-ip:" + remoteAddress), properties.getResetIpMaxFailures(), now);
            recordKey(keyHash("reset-account:" + normalize(email)), properties.getResetMaxFailures(), now);
        });
    }

    @Override
    public void recordSuccess(String email) {
        jdbc.update("delete from password_reset_attempt_buckets where key_hash = ?",
                keyHash("reset-account:" + normalize(email)));
    }

    private void recordKey(String key, int threshold, Instant now) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM password_reset_attempt_buckets WHERE key_hash = ?)",
                Boolean.class, key);
        if (!Boolean.TRUE.equals(exists)) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM password_reset_attempt_buckets", Integer.class);
            if (count == null || count >= properties.getResetMaxTrackedKeys()) return;
            jdbc.update("""
                    INSERT INTO password_reset_attempt_buckets
                        (key_hash, failure_count, window_started_at, blocked_until, block_level, last_requested_at)
                    VALUES (?, 0, ?, NULL, 0, ?)
                    ON CONFLICT (key_hash) DO NOTHING
                    """, key, Timestamp.from(now), Timestamp.from(now));
        }
        java.util.List<Bucket> rows = jdbc.query(
                "select failure_count, window_started_at, blocked_until, block_level "
                        + "from password_reset_attempt_buckets where key_hash = ? for update",
                (rs, row) -> new Bucket(rs.getInt("failure_count"),
                        rs.getTimestamp("window_started_at").toInstant(),
                        rs.getTimestamp("blocked_until") == null
                                ? null : rs.getTimestamp("blocked_until").toInstant(),
                        rs.getInt("block_level")),
                key);
        if (rows.isEmpty()) return;
        Bucket bucket = rows.get(0);
        if (bucket.blockedUntil != null && now.isBefore(bucket.blockedUntil)) return;
        int failures = bucket.failureCount;
        Instant windowStart = bucket.windowStartedAt;
        if (!now.isBefore(windowStart.plus(properties.getWindow()))) {
            failures = 0;
            windowStart = now;
        }
        failures++;
        Instant blockedUntil = null;
        int blockLevel = bucket.blockLevel;
        if (failures >= threshold) {
            blockedUntil = now.plus(blockDuration(blockLevel));
            blockLevel++;
            failures = 0;
            windowStart = now;
        }
        jdbc.update("""
                update password_reset_attempt_buckets
                set failure_count = ?, window_started_at = ?, blocked_until = ?,
                    block_level = ?, last_requested_at = ?
                where key_hash = ?
                """, failures, Timestamp.from(windowStart),
                blockedUntil == null ? null : Timestamp.from(blockedUntil),
                blockLevel, Timestamp.from(now), key);
    }

    private String normalize(String email) {
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

    private synchronized void cleanupExpired() {
        Instant now = clock.instant();
        if (now.isBefore(nextCleanup)) return;
        jdbc.update("""
                delete from password_reset_attempt_buckets
                where last_requested_at < ?
                  and (blocked_until is null or blocked_until <= ?)
                """,
                Timestamp.from(now.minus(properties.getWindow().plus(properties.getMaxBlock()))),
                Timestamp.from(now));
        nextCleanup = now.plus(Duration.ofMinutes(1));
    }

    private Duration blockDuration(int level) {
        long multiplier = 1L << Math.min(level, 30);
        try {
            Duration duration = properties.getBaseBlock().multipliedBy(multiplier);
            return duration.compareTo(properties.getMaxBlock()) > 0
                    ? properties.getMaxBlock() : duration;
        } catch (ArithmeticException exception) {
            return properties.getMaxBlock();
        }
    }

    private record Bucket(int failureCount, Instant windowStartedAt, Instant blockedUntil, int blockLevel) {}
}
