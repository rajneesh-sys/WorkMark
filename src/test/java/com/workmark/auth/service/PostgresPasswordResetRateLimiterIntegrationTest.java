package com.workmark.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "app.jwt.secret=reset-limiter-integration-secret-with-more-than-32-characters",
        "app.auth.login-rate-limit.reset-max-failures=1000",
        "app.auth.login-rate-limit.reset-ip-max-failures=1000",
        "app.auth.login-rate-limit.reset-max-tracked-keys=10000"
})
@Testcontainers(disabledWithoutDocker = true)
class PostgresPasswordResetRateLimiterIntegrationTest {
    private static final String SECRET = "reset-limiter-integration-secret-with-more-than-32-characters";
    private static final long PREVIOUS_GLOBAL_LOCK_KEY = 6288528922353146195L;

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
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;

    @BeforeEach
    void clearBuckets() {
        jdbc.update("DELETE FROM password_reset_attempt_buckets");
    }

    @Test
    void concurrentRequestsForOneAccountDoNotLoseBucketUpdates() throws Exception {
        int workers = 16;
        LoginRateLimitProperties config = configuredProperties();
        PostgresPasswordResetRateLimiter limiter = limiter(config);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var tasks = java.util.stream.IntStream.range(0, workers)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        limiter.recordRequest("192.0.2." + (index + 1), "same@example.com");
                        return null;
                    })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(workers, jdbc.queryForObject(
                "SELECT failure_count FROM password_reset_attempt_buckets WHERE key_hash = ?",
                Integer.class, hash("reset-account:same@example.com")));
    }

    @Test
    void unrelatedAccountBucketsProceedWithoutTheFormerGlobalLock() throws Exception {
        PostgresPasswordResetRateLimiter limiter = limiter(configuredProperties());
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        try {
            var holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.execute("SELECT pg_advisory_xact_lock(" + PREVIOUS_GLOBAL_LOCK_KEY + ")");
                lockAcquired.countDown();
                try {
                    if (!releaseLock.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release test advisory lock");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding test advisory lock", exception);
                }
            }));
            assertTrue(lockAcquired.await(10, TimeUnit.SECONDS));
            var unrelated = executor.submit(() -> {
                limiter.recordRequest("192.0.2.200", "independent@example.com");
                return true;
            });
            assertTrue(unrelated.get(3, TimeUnit.SECONDS));
            releaseLock.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    private LoginRateLimitProperties configuredProperties() {
        LoginRateLimitProperties configured = new LoginRateLimitProperties();
        configured.setResetMaxFailures(1000);
        configured.setResetIpMaxFailures(1000);
        configured.setResetMaxTrackedKeys(10_000);
        configured.setWindow(Duration.ofMinutes(5));
        configured.setBaseBlock(Duration.ofSeconds(1));
        configured.setMaxBlock(Duration.ofMinutes(1));
        return configured;
    }

    private PostgresPasswordResetRateLimiter limiter(LoginRateLimitProperties configured) {
        return new PostgresPasswordResetRateLimiter(jdbc, transactionManager, configured, clock, SECRET);
    }

    private String hash(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }
}
