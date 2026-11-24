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
        Instant now = clock.instant();
        for (String key : new String[]{keyHash("reset-ip:" + remoteAddress),
                keyHash("reset-account:" + normalize(email))}) {
            Timestamp blocked = jdbc.query(
                    "select blocked_until from password_reset_attempt_buckets where key_hash = ?",
                    rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1) : null, key);
            if (blocked != null && now.isBefore(blocked.toInstant())) return false;
        }
        return true;
    }

    @Override
    public void recordRequest(String remoteAddress, String email) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
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
        jdbc.update("""
                insert into password_reset_attempt_buckets
                    (key_hash, failure_count, window_started_at, blocked_until, block_level, last_requested_at)
                values (?, 0, ?, null, 0, ?)
                on conflict (key_hash) do nothing
                """, key, Timestamp.from(now), Timestamp.from(now));
        jdbc.execute("select pg_advisory_xact_lock(" + (6288528922353146195L) + ")");
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
