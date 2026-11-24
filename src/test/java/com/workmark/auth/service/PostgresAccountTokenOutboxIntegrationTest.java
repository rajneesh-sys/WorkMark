package com.workmark.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "app.jwt.secret=outbox-integration-jwt-secret-with-more-than-32-characters",
        "app.auth.outbox.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.auth.outbox.max-attempts=2",
        "app.auth.outbox.base-retry-delay=PT1S",
        "app.auth.outbox.max-retry-delay=PT10S"
})
@Testcontainers(disabledWithoutDocker = true)
class PostgresAccountTokenOutboxIntegrationTest {
    private static final String TOKEN = "private-one-time-token-never-store-plaintext";
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withInitScript("postgres-test-init.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PostgresAccountTokenOutbox outbox;
    @Autowired ObjectMapper objectMapper;
    @Autowired MeterRegistry meters;
    @Autowired Clock clock;
    @Autowired Environment environment;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean AccountTokenDelivery delivery;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM account_token_outbox");
        reset(delivery);
    }

    @Test
    void queuedMessageRollsBackWithTheEnclosingTokenTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            enqueue(TOKEN, clock.instant().plusSeconds(600));
            status.setRollbackOnly();
        });

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_token_outbox", Integer.class));
    }

    @Test
    void encryptsPendingPayloadAndRemovesItAfterSuccessfulDelivery() {
        enqueue(TOKEN, clock.instant().plusSeconds(600));
        UUID id = latestId();
        byte[] ciphertext = jdbc.queryForObject(
                "SELECT payload_ciphertext FROM account_token_outbox WHERE id = ?", byte[].class, id);
        assertFalse(new String(ciphertext, java.nio.charset.StandardCharsets.UTF_8).contains(TOKEN));
        double deliveredBefore = meters.get("workmark.account_token_outbox.deliveries")
                .tag("outcome", "delivered").counter().count();

        doNothing().when(delivery).deliver(anyString(), anyString(), anyString(), any(),
                any(AccountTokenDelivery.Purpose.class), anyString());
        outbox.processPendingBatch();

        assertEquals("DELIVERED", status(id));
        assertNull(jdbc.queryForObject(
                "SELECT payload_ciphertext FROM account_token_outbox WHERE id = ?", byte[].class, id));
        verify(delivery).deliver(eq("person@example.com"), eq("Example Person"), eq(TOKEN),
                any(Instant.class), eq(AccountTokenDelivery.Purpose.PASSWORD_SETUP), eq(id.toString()));
        assertEquals(deliveredBefore + 1.0, meters.get("workmark.account_token_outbox.deliveries")
                .tag("outcome", "delivered").counter().count());
    }

    @Test
    void retriesTemporaryFailureWithSameTokenAndExhaustsAfterMaximumAttempts() {
        enqueue(TOKEN, clock.instant().plusSeconds(600));
        UUID id = latestId();
        byte[] ciphertextBeforeRetry = jdbc.queryForObject(
                "SELECT payload_ciphertext FROM account_token_outbox WHERE id = ?", byte[].class, id);
        doThrow(new MailSendException("SMTP unavailable"))
                .doNothing().when(delivery).deliver(anyString(), anyString(), anyString(), any(),
                        any(AccountTokenDelivery.Purpose.class), anyString());

        outbox.processPendingBatch();
        assertEquals("PENDING", status(id));
        assertTrue(java.util.Arrays.equals(ciphertextBeforeRetry, jdbc.queryForObject(
                "SELECT payload_ciphertext FROM account_token_outbox WHERE id = ?", byte[].class, id)));
        assertEquals("SMTP_SEND", jdbc.queryForObject(
                "SELECT last_failure_category FROM account_token_outbox WHERE id = ?", String.class, id));
        jdbc.update("UPDATE account_token_outbox SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), id);
        outbox.processPendingBatch();

        assertEquals("DELIVERED", status(id));
        verify(delivery, times(2)).deliver(anyString(), anyString(), anyString(), any(),
                any(AccountTokenDelivery.Purpose.class), anyString());

        enqueue("another-token", clock.instant().plusSeconds(600));
        UUID exhaustedId = latestId();
        doThrow(new MailSendException("SMTP unavailable")).when(delivery).deliver(
                anyString(), anyString(), anyString(), any(), any(AccountTokenDelivery.Purpose.class), anyString());
        outbox.processPendingBatch();
        jdbc.update("UPDATE account_token_outbox SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), exhaustedId);
        outbox.processPendingBatch();
        assertEquals("EXHAUSTED", status(exhaustedId));
        assertNull(jdbc.queryForObject(
                "SELECT payload_ciphertext FROM account_token_outbox WHERE id = ?", byte[].class, exhaustedId));
    }

    @Test
    void expiredTokenIsNotSentAndPendingMessagesSurviveAWorkerRestart() {
        enqueue("expired-token", clock.instant().minusSeconds(1));
        UUID expiredId = latestId();
        outbox.processPendingBatch();
        assertEquals("EXPIRED", status(expiredId));
        verifyNoInteractions(delivery);

        enqueue(TOKEN, clock.instant().plusSeconds(600));
        UUID pendingId = latestId();
        PostgresAccountTokenOutbox restartedWorker = new PostgresAccountTokenOutbox(
                jdbc, transactionManager, objectMapper, delivery, meters, clock, environment, KEY,
                20, 2, java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10),
                java.time.Duration.ofMinutes(2), java.time.Duration.ofHours(720));
        restartedWorker.validateConfiguration();
        doNothing().when(delivery).deliver(anyString(), anyString(), anyString(), any(),
                any(AccountTokenDelivery.Purpose.class), anyString());

        restartedWorker.processPendingBatch();

        assertEquals("DELIVERED", status(pendingId));
    }

    @Test
    void concurrentWorkersCannotClaimTheSameMessageAtOnce() throws Exception {
        enqueue(TOKEN, clock.instant().plusSeconds(600));
        UUID id = latestId();
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        doAnswer(invocation -> {
            deliveryStarted.countDown();
            assertTrue(releaseDelivery.await(10, TimeUnit.SECONDS));
            return null;
        }).when(delivery).deliver(anyString(), anyString(), anyString(), any(),
                any(AccountTokenDelivery.Purpose.class), anyString());
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            var first = workers.submit(outbox::processPendingBatch);
            assertTrue(deliveryStarted.await(10, TimeUnit.SECONDS));
            assertEquals(0, outbox.processPendingBatch());
            releaseDelivery.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            releaseDelivery.countDown();
            workers.shutdownNow();
        }
        assertEquals("DELIVERED", status(id));
        verify(delivery, times(1)).deliver(anyString(), anyString(), anyString(), any(),
                any(AccountTokenDelivery.Purpose.class), anyString());
    }

    private void enqueue(String token, Instant expiresAt) {
        outbox.enqueue("person@example.com", "Example Person", token, expiresAt,
                AccountTokenDelivery.Purpose.PASSWORD_SETUP);
    }

    private UUID latestId() {
        return jdbc.queryForObject("SELECT id FROM account_token_outbox ORDER BY created_at DESC LIMIT 1",
                UUID.class);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM account_token_outbox WHERE id = ?", String.class, id);
    }
}
