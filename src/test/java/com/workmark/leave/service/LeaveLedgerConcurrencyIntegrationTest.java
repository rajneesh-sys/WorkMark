package com.workmark.leave.service;

import com.workmark.auth.entity.UserAccount;
import com.workmark.auth.entity.UserRole;
import com.workmark.auth.repository.UserAccountRepository;
import com.workmark.common.exception.ApiException;
import com.workmark.company.entity.Company;
import com.workmark.company.repository.CompanyRepository;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.leave.dto.LeaveActionRequest;
import com.workmark.leave.entity.LeaveBalanceEntry;
import com.workmark.leave.entity.LeavePolicy;
import com.workmark.leave.entity.LeaveRequest;
import com.workmark.leave.entity.LeaveStatus;
import com.workmark.leave.entity.LeaveTransactionType;
import com.workmark.leave.entity.LeaveType;
import com.workmark.leave.repository.LeaveBalanceEntryRepository;
import com.workmark.leave.repository.LeavePolicyRepository;
import com.workmark.leave.repository.LeaveRequestRepository;
import com.workmark.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "app.jwt.secret=leave-concurrency-integration-secret-with-more-than-32-characters")
@Testcontainers
class LeaveLedgerConcurrencyIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withInitScript("postgres-test-init.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired CompanyRepository companies;
    @Autowired EmployeeRepository employees;
    @Autowired UserAccountRepository users;
    @Autowired LeavePolicyRepository policies;
    @Autowired LeaveBalanceEntryRepository entries;
    @Autowired LeaveRequestRepository requests;
    @Autowired LeaveLedgerService ledger;
    @Autowired LeaveService leaveService;

    private Company company;
    private Employee employee;
    private UserAccount approver;
    private CurrentUser currentUser;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        company = companies.save(new Company("Concurrency " + suffix, suffix + "@example.com", null, null));
        employee = employees.save(new Employee(company.getId(), "E-" + suffix, "Leave", "Person",
                "leave-" + suffix + "@example.com", null, null, null, null, LocalDate.now().minusYears(1)));
        approver = users.save(new UserAccount(company.getId(), "Approver", "approver-" + suffix + "@example.com",
                "stored-hash", UserRole.HR));
        currentUser = new CurrentUser(approver.getId(), company.getId(), null, approver.getEmail(),
                approver.getPasswordHash(), UserRole.HR, approver.getTokenVersion());
        policies.save(new LeavePolicy(company.getId(), LeaveType.CASUAL, BigDecimal.TEN,
                BigDecimal.ZERO, 0, false, true));
        entries.save(new LeaveBalanceEntry(company.getId(), employee.getId(), LeaveType.CASUAL,
                LeaveTransactionType.ALLOCATION, BigDecimal.valueOf(5), null, approver.getId(),
                "Initial allocation", Instant.now().minusSeconds(10), null));
    }

    @Test
    void concurrentApprovalsAndCancellationsConsumeAndRestoreExactlyOnce() throws Exception {
        LeaveRequest request = request(BigDecimal.valueOf(2));
        List<Boolean> approvalResults = concurrently(
                () -> leaveService.approve(company.getId(), currentUser, request.getId(),
                        new LeaveActionRequest("Approved")),
                () -> leaveService.approve(company.getId(), currentUser, request.getId(),
                        new LeaveActionRequest("Approved")));
        assertEquals(1, approvalResults.stream().filter(Boolean::booleanValue).count());
        assertEquals(LeaveStatus.APPROVED,
                requests.findByIdAndCompanyId(request.getId(), company.getId()).orElseThrow().getStatus());
        assertEquals(1, countTransaction(request.getId(), LeaveTransactionType.CONSUMPTION));
        assertEquals(BigDecimal.valueOf(3).setScale(2), balance());

        List<Boolean> cancellationResults = concurrently(
                () -> leaveService.cancel(company.getId(), currentUser, request.getId(),
                        new LeaveActionRequest("Cancelled")),
                () -> leaveService.cancel(company.getId(), currentUser, request.getId(),
                        new LeaveActionRequest("Cancelled")));
        assertEquals(1, cancellationResults.stream().filter(Boolean::booleanValue).count());
        assertEquals(LeaveStatus.CANCELLED,
                requests.findByIdAndCompanyId(request.getId(), company.getId()).orElseThrow().getStatus());
        assertEquals(1, countTransaction(request.getId(), LeaveTransactionType.RESTORATION));
        assertEquals(BigDecimal.valueOf(5).setScale(2), balance());
    }

    @Test
    void concurrentRejectionAndApprovalProduceOneConsistentTransition() throws Exception {
        LeaveRequest request = request(BigDecimal.valueOf(2));
        List<Boolean> results = concurrently(
                () -> leaveService.reject(company.getId(), currentUser, request.getId(),
                        new LeaveActionRequest("Rejected")),
                () -> leaveService.approve(company.getId(), currentUser, request.getId(),
                        new LeaveActionRequest("Approved")));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        LeaveRequest persisted = requests.findByIdAndCompanyId(request.getId(), company.getId()).orElseThrow();
        if (persisted.getStatus() == LeaveStatus.REJECTED) {
            assertEquals(0, countTransaction(request.getId(), LeaveTransactionType.CONSUMPTION));
            assertEquals(BigDecimal.valueOf(5).setScale(2), balance());
        } else {
            assertEquals(LeaveStatus.APPROVED, persisted.getStatus());
            assertEquals(1, countTransaction(request.getId(), LeaveTransactionType.CONSUMPTION));
            assertEquals(BigDecimal.valueOf(3).setScale(2), balance());
        }
    }

    @Test
    void concurrentApprovalAndCancellationCannotLeaveUnbalancedLedgerState() throws Exception {
        LeaveRequest request = request(BigDecimal.valueOf(2));
        concurrently(
                () -> leaveService.approve(company.getId(), currentUser, request.getId(),
                        new LeaveActionRequest("Approved")),
                () -> leaveService.cancel(company.getId(), currentUser, request.getId(),
                        new LeaveActionRequest("Cancelled")));

        LeaveStatus status = requests.findByIdAndCompanyId(request.getId(), company.getId()).orElseThrow().getStatus();
        long consumptions = countTransaction(request.getId(), LeaveTransactionType.CONSUMPTION);
        long restorations = countTransaction(request.getId(), LeaveTransactionType.RESTORATION);
        if (status == LeaveStatus.APPROVED) {
            assertEquals(1, consumptions);
            assertEquals(0, restorations);
            assertEquals(BigDecimal.valueOf(3).setScale(2), balance());
        } else {
            assertEquals(LeaveStatus.CANCELLED, status);
            assertTrue(consumptions == restorations);
            assertEquals(BigDecimal.valueOf(5).setScale(2), balance());
        }
    }

    @Test
    void concurrentLedgerConsumptionCannotOverdrawBalanceOrDuplicateAnEntry() throws Exception {
        LeaveRequest request = request(BigDecimal.valueOf(4));
        List<Boolean> results = concurrently(
                () -> ledger.consume(company.getId(), employee.getId(), LeaveType.CASUAL,
                        BigDecimal.valueOf(4), request.getId(), approver.getId(), "Consume"),
                () -> ledger.consume(company.getId(), employee.getId(), LeaveType.CASUAL,
                        BigDecimal.valueOf(4), request.getId(), approver.getId(), "Consume"));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, countTransaction(request.getId(), LeaveTransactionType.CONSUMPTION));
        assertEquals(BigDecimal.ONE.setScale(2), balance());
    }

    @Test
    void concurrentRestorationIsRecordedOnce() throws Exception {
        LeaveRequest request = request(BigDecimal.valueOf(2));
        ledger.consume(company.getId(), employee.getId(), LeaveType.CASUAL, BigDecimal.valueOf(2),
                request.getId(), approver.getId(), "Consume");

        List<Boolean> results = concurrently(
                () -> ledger.restore(company.getId(), employee.getId(), LeaveType.CASUAL,
                        BigDecimal.valueOf(2), request.getId(), approver.getId(), "Restore"),
                () -> ledger.restore(company.getId(), employee.getId(), LeaveType.CASUAL,
                        BigDecimal.valueOf(2), request.getId(), approver.getId(), "Restore"));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, countTransaction(request.getId(), LeaveTransactionType.RESTORATION));
        assertEquals(BigDecimal.valueOf(5).setScale(2), balance());
    }

    private LeaveRequest request(BigDecimal days) {
        LocalDate start = LocalDate.now().plusDays(30);
        return requests.save(new LeaveRequest(company.getId(), employee.getId(), LeaveType.CASUAL,
                start, start.plusDays(days.intValue() - 1), days, "Concurrent leave"));
    }

    private long countTransaction(UUID requestId, LeaveTransactionType type) {
        return entries.countByLeaveRequestIdAndTransactionType(requestId, type);
    }

    private BigDecimal balance() {
        return entries.balance(company.getId(), employee.getId(), LeaveType.CASUAL.name(), Instant.now());
    }

    private List<Boolean> concurrently(CheckedRunnable first, CheckedRunnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (CheckedRunnable operation : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        operation.run();
                        return true;
                    } catch (ApiException | org.springframework.dao.DataIntegrityViolationException exception) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
