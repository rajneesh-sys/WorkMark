package com.workmark.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class PostgresAccountTokenOutbox implements AccountTokenOutbox {
    private static final Logger log = LoggerFactory.getLogger(PostgresAccountTokenOutbox.class);
    private static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final AccountTokenDelivery delivery;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Environment environment;
    private final String encryptionKey;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration baseRetryDelay;
    private final Duration maxRetryDelay;
    private final Duration processingLease;
    private final Duration retention;
    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKeySpec key;

    public PostgresAccountTokenOutbox(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                      ObjectMapper objectMapper, AccountTokenDelivery delivery,
                                      MeterRegistry meters, Clock clock, Environment environment,
                                      @Value("${app.auth.outbox.encryption-key:}") String encryptionKey,
                                      @Value("${app.auth.outbox.batch-size:20}") int batchSize,
                                      @Value("${app.auth.outbox.max-attempts:8}") int maxAttempts,
                                      @Value("${app.auth.outbox.base-retry-delay:PT5S}") Duration baseRetryDelay,
                                      @Value("${app.auth.outbox.max-retry-delay:PT1H}") Duration maxRetryDelay,
                                      @Value("${app.auth.outbox.processing-lease:PT2M}") Duration processingLease,
                                      @Value("${app.auth.outbox.retention:PT720H}") Duration retention) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.delivery = delivery;
        this.meters = meters;
        this.clock = clock;
        this.environment = environment;
        this.encryptionKey = encryptionKey;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.baseRetryDelay = baseRetryDelay;
        this.maxRetryDelay = maxRetryDelay;
        this.processingLease = processingLease;
        this.retention = retention;
    }

    @PostConstruct
    void validateConfiguration() {
        if (batchSize < 1 || maxAttempts < 1 || !positive(baseRetryDelay)
                || !positive(maxRetryDelay) || maxRetryDelay.compareTo(baseRetryDelay) < 0
                || !positive(processingLease) || !positive(retention)) {
            throw new IllegalStateException("Account token outbox configuration is invalid");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptionKey);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("Expected 32 bytes");
            }
            key = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            if (environment.acceptsProfiles(Profiles.of("prod", "staging"))) {
                throw new IllegalStateException(
                        "ACCOUNT_TOKEN_OUTBOX_KEY must be Base64-encoded 32-byte key material", exception);
            }
        }
    }

    @Override
    public void enqueue(String recipient, String displayName, String token, Instant expiresAt,
                       AccountTokenDelivery.Purpose purpose) {
        if (key == null) {
            throw new IllegalStateException("A valid account token outbox encryption key is required");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        TokenPayload payload = new TokenPayload(recipient, displayName, token, expiresAt, purpose);
        byte[] nonce = new byte[NONCE_LENGTH];
        secureRandom.nextBytes(nonce);
        byte[] ciphertext = encrypt(payload, nonce);
        jdbc.update("""
                INSERT INTO account_token_outbox
                    (id, payload_nonce, payload_ciphertext, status, attempt_count, next_attempt_at,
                     expires_at, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', 0, ?, ?, ?, ?)
                """, id, nonce, ciphertext, sqlTime(now), sqlTime(expiresAt), sqlTime(now), sqlTime(now));
        meters.counter("workmark.account_token_outbox.enqueued").increment();
    }

    public int processPendingBatch() {
        Instant now = clock.instant();
        expireStale(now);
        List<ClaimedMessage> messages = claim(now);
        for (ClaimedMessage message : messages) {
            process(message);
        }
        cleanup(now);
        return messages.size();
    }

    private void process(ClaimedMessage message) {
        try {
            TokenPayload payload = decrypt(message.ciphertext, message.nonce);
            if (!payload.expiresAt().isAfter(clock.instant())) {
                markExpired(message);
                return;
            }
            delivery.deliver(payload.recipient(), payload.displayName(), payload.token(), payload.expiresAt(),
                    payload.purpose(), message.id.toString());
            if (complete(message, "DELIVERED", null, true)) {
                meters.counter("workmark.account_token_outbox.deliveries", "outcome", "delivered").increment();
                log.info("Account token email delivered; messageId={} attempt={}",
                        message.id, message.attemptCount);
            }
        } catch (RuntimeException exception) {
            String category = failureCategory(exception);
            boolean exhausted = message.attemptCount >= maxAttempts;
            Instant retryAt = exhausted ? null : clock.instant().plus(retryDelay(message.attemptCount));
            if (fail(message, category, exhausted, retryAt)) {
                meters.counter("workmark.account_token_outbox.deliveries", "outcome",
                        exhausted ? "exhausted" : "retry").increment();
                log.warn("Account token email delivery failed; messageId={} attempt={} category={} exhausted={}",
                        message.id, message.attemptCount, category, exhausted);
            }
        }
    }

    private List<ClaimedMessage> claim(Instant now) {
        UUID leaseToken = UUID.randomUUID();
        Instant leaseUntil = now.plus(processingLease);
        return transactions.execute(status -> jdbc.query("""
                WITH candidates AS (
                    SELECT id
                    FROM account_token_outbox
                    WHERE expires_at > ?
                      AND ((status = 'PENDING' AND next_attempt_at <= ?)
                           OR (status = 'PROCESSING' AND lease_until <= ?))
                    ORDER BY next_attempt_at, created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE account_token_outbox AS outbox
                SET status = 'PROCESSING', attempt_count = outbox.attempt_count + 1,
                    lease_until = ?, lease_token = ?, updated_at = ?
                FROM candidates
                WHERE outbox.id = candidates.id
                RETURNING outbox.id, outbox.payload_nonce, outbox.payload_ciphertext,
                          outbox.attempt_count, outbox.lease_token
                """, (rs, row) -> new ClaimedMessage(rs.getObject("id", UUID.class),
                rs.getBytes("payload_nonce"), rs.getBytes("payload_ciphertext"),
                rs.getInt("attempt_count"), rs.getObject("lease_token", UUID.class)),
                sqlTime(now), sqlTime(now), sqlTime(now), batchSize, sqlTime(leaseUntil), leaseToken, sqlTime(now)));
    }

    private void expireStale(Instant now) {
        int expired = jdbc.update("""
                UPDATE account_token_outbox
                SET status = 'EXPIRED', payload_nonce = NULL, payload_ciphertext = NULL,
                    lease_until = NULL, lease_token = NULL, last_failure_category = 'TOKEN_EXPIRED',
                    updated_at = ?
                WHERE expires_at <= ?
                  AND (status = 'PENDING' OR (status = 'PROCESSING' AND lease_until <= ?))
                """, sqlTime(now), sqlTime(now), sqlTime(now));
        if (expired > 0) {
            meters.counter("workmark.account_token_outbox.deliveries", "outcome", "expired")
                    .increment(expired);
        }
    }

    private void markExpired(ClaimedMessage message) {
        int updated = transactions.execute(status -> jdbc.update("""
                UPDATE account_token_outbox
                SET status = 'EXPIRED', payload_nonce = NULL, payload_ciphertext = NULL,
                    lease_until = NULL, lease_token = NULL, last_failure_category = 'TOKEN_EXPIRED',
                    updated_at = ?
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, sqlTime(clock.instant()), message.id, message.leaseToken));
        if (updated > 0) {
            meters.counter("workmark.account_token_outbox.deliveries", "outcome", "expired").increment();
        }
    }

    private boolean complete(ClaimedMessage message, String status, String category, boolean delivered) {
        return transactions.execute(statusObject -> jdbc.update("""
                UPDATE account_token_outbox
                SET status = ?, payload_nonce = NULL, payload_ciphertext = NULL,
                    lease_until = NULL, lease_token = NULL, last_failure_category = ?,
                    delivered_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, status, category, sqlTime(delivered ? clock.instant() : null), sqlTime(clock.instant()),
                message.id, message.leaseToken)) > 0;
    }

    private boolean fail(ClaimedMessage message, String category, boolean exhausted, Instant retryAt) {
        return transactions.execute(status -> jdbc.update("""
                UPDATE account_token_outbox
                SET status = ?, payload_nonce = CASE WHEN ? THEN NULL ELSE payload_nonce END,
                    payload_ciphertext = CASE WHEN ? THEN NULL ELSE payload_ciphertext END,
                    lease_until = NULL, lease_token = NULL, last_failure_category = ?,
                    next_attempt_at = COALESCE(?, next_attempt_at), updated_at = ?
                WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                """, exhausted ? "EXHAUSTED" : "PENDING", exhausted, exhausted, category,
                sqlTime(retryAt), sqlTime(clock.instant()), message.id, message.leaseToken)) > 0;
    }

    private void cleanup(Instant now) {
        jdbc.update("""
                DELETE FROM account_token_outbox
                WHERE status IN ('DELIVERED', 'EXHAUSTED', 'EXPIRED') AND updated_at < ?
                """, sqlTime(now.minus(retention)));
    }

    private byte[] encrypt(TokenPayload payload, byte[] nonce) {
        try {
            byte[] plaintext = objectMapper.writeValueAsBytes(payload);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException | JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encrypt account token delivery payload", exception);
        }
    }

    private TokenPayload decrypt(byte[] ciphertext, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            return objectMapper.readValue(cipher.doFinal(ciphertext), TokenPayload.class);
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("Unable to decrypt account token delivery payload", exception);
        }
    }

    private Duration retryDelay(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 30);
        try {
            Duration delay = baseRetryDelay.multipliedBy(1L << exponent);
            return delay.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : delay;
        } catch (ArithmeticException exception) {
            return maxRetryDelay;
        }
    }

    private String failureCategory(RuntimeException exception) {
        if (exception instanceof org.springframework.mail.MailAuthenticationException) {
            return "SMTP_AUTHENTICATION";
        }
        if (exception instanceof org.springframework.mail.MailSendException) {
            return "SMTP_SEND";
        }
        if (exception instanceof org.springframework.mail.MailException) {
            return "SMTP_FAILURE";
        }
        return "DELIVERY_FAILURE";
    }

    private boolean positive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }

    private Timestamp sqlTime(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record TokenPayload(String recipient, String displayName, String token, Instant expiresAt,
                               AccountTokenDelivery.Purpose purpose) {}
    private record ClaimedMessage(UUID id, byte[] nonce, byte[] ciphertext, int attemptCount,
                                  UUID leaseToken) {}
}
