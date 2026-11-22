package com.workmark.company;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workmark.auth.bootstrap.SuperAdminBootstrapService;
import com.workmark.auth.entity.UserRole;
import com.workmark.auth.entity.UserAccount;
import com.workmark.auth.repository.UserAccountRepository;
import com.workmark.auth.dto.LoginRequest;
import com.workmark.attendance.entity.Attendance;
import com.workmark.attendance.repository.AttendanceRepository;
import com.workmark.company.repository.CompanyRepository;
import com.workmark.company.entity.Company;
import com.workmark.employee.entity.Employee;
import com.workmark.employee.entity.EmployeeStatus;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.leave.entity.LeaveRequest;
import com.workmark.leave.entity.LeaveStatus;
import com.workmark.leave.entity.LeaveType;
import com.workmark.leave.repository.LeaveRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.jwt.secret=integration-test-jwt-secret-with-more-than-32-characters",
        "app.bootstrap.super-admin.enabled=true",
        "app.bootstrap.super-admin.name=Integration Super Admin",
        "app.bootstrap.super-admin.email=integration-super@example.com",
        "app.bootstrap.super-admin.password=IntegrationSuperPassword123",
        "app.api-docs.public=false",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CompanyOnboardingIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserAccountRepository users;
    @Autowired CompanyRepository companies;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired LeaveRequestRepository leaveRequests;
    @Autowired AttendanceRepository attendances;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SuperAdminBootstrapService bootstrapService;

    @BeforeEach
    void ensureSuperAdminExists() {
        bootstrapService.bootstrapIfEnabled();
    }

    @Test
    void bootstrapCreatesOnlySuperAdminWithoutTenant() {
        var admins = users.findByEmailIgnoreCase("integration-super@example.com").orElseThrow();

        assertEquals(1, users.countByRole(UserRole.SUPER_ADMIN));
        assertEquals(UserRole.SUPER_ADMIN, admins.getRole());
        assertNull(admins.getCompanyId());
        assertTrue(admins.isActive());
        assertTrue(passwordEncoder.matches("IntegrationSuperPassword123", admins.getPasswordHash()));
    }

    @Test
    void concurrentBootstrapCallsRemainIdempotent() throws Exception {
        users.findByEmailIgnoreCase("integration-super@example.com").ifPresent(admin -> {
            users.delete(admin);
            users.flush();
        });
        int workerCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < workerCount; i++) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent bootstrap test did not start");
                    }
                    bootstrapService.bootstrapIfEnabled();
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, users.countByRole(UserRole.SUPER_ADMIN));
    }

    @Test
    void companyRegistrationIsNotPublic() throws Exception {
        String request = companyRequest();

        mockMvc.perform(post("/api/v1/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void enabledApiDocumentationRequiresAuthenticationWhenNotPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v3/api-docs").with(user("docs-reader").roles("HR")))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "COMPANY_ADMIN")
    void companyAdminCannotRegisterAnotherCompany() throws Exception {
        mockMvc.perform(post("/api/v1/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(companyRequest()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void superAdminRegistersCompanyAndOneTimeAdminActivation() throws Exception {
        String companyEmail = "company-" + UUID.randomUUID() + "@example.com";
        String adminEmail = "admin-" + UUID.randomUUID() + "@example.com";
        String request = objectMapper.writeValueAsString(java.util.Map.of(
                "companyName", "Onboarded Ltd",
                "companyEmail", companyEmail,
                "adminName", "First Admin",
                "adminEmail", adminEmail));

        String response = mockMvc.perform(post("/api/v1/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).get("data");
        String token = data.get("passwordSetupToken").asText();
        var company = companies.findById(UUID.fromString(data.get("companyId").asText())).orElseThrow();
        var pendingAdmin = users.findByEmailIgnoreCase(adminEmail).orElseThrow();

        assertEquals(company.getId(), pendingAdmin.getCompanyId());
        assertEquals(UserRole.COMPANY_ADMIN, pendingAdmin.getRole());
        assertFalse(pendingAdmin.isActive());
        assertNull(pendingAdmin.getPasswordHash());
        assertNotEquals(token, pendingAdmin.getActivationTokenHash());

        String activation = objectMapper.writeValueAsString(java.util.Map.of(
                "token", token, "password", "CompanyAdminPassword123"));
        mockMvc.perform(post("/api/v1/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activation))
                .andExpect(status().isOk());

        var activatedAdmin = users.findByEmailIgnoreCase(adminEmail).orElseThrow();
        assertTrue(activatedAdmin.isActive());
        assertEquals(UserRole.COMPANY_ADMIN, activatedAdmin.getRole());
        assertTrue(passwordEncoder.matches("CompanyAdminPassword123", activatedAdmin.getPasswordHash()));
        assertNull(activatedAdmin.getActivationTokenHash());

        mockMvc.perform(post("/api/v1/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activation))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managerAndEmployeeAccessIsScopedWhileHrRetainsCompanyWideAccess() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Company company = company(suffix);
        Employee managerEmployee = employee(company.getId(), "M-" + suffix, null);
        managerEmployee = employeeRepository.save(managerEmployee);
        Employee report = employee(company.getId(), "R-" + suffix, managerEmployee.getId());
        report = employeeRepository.save(report);
        Employee outsider = employee(company.getId(), "O-" + suffix, null);
        outsider = employeeRepository.save(outsider);
        Company otherCompany = company("other-" + suffix);
        Employee otherCompanyEmployee = employeeRepository.save(
                employee(otherCompany.getId(), "X-" + suffix, null));
        UserAccount managerAccount = account(company.getId(), managerEmployee.getId(), UserRole.MANAGER,
                "manager-" + suffix + "@example.com", "ManagerPassword123");
        UserAccount employeeAccount = account(company.getId(), report.getId(), UserRole.EMPLOYEE,
                "employee-" + suffix + "@example.com", "EmployeePassword123");
        UserAccount hrAccount = account(company.getId(), null, UserRole.HR,
                "hr-" + suffix + "@example.com", "HrPassword123");
        UserAccount adminAccount = account(company.getId(), null, UserRole.COMPANY_ADMIN,
                "admin-" + suffix + "@example.com", "AdminPassword123");

        LocalDate attendanceDate = LocalDate.now().minusDays(1);
        attendances.save(new Attendance(company.getId(), report.getId(), attendanceDate,
                attendanceDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        attendances.save(new Attendance(company.getId(), outsider.getId(), attendanceDate,
                attendanceDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        LeaveRequest reportLeave = leaveRequests.save(new LeaveRequest(company.getId(), report.getId(),
                LeaveType.CASUAL, attendanceDate, attendanceDate, BigDecimal.ONE, "report request"));
        LeaveRequest outsiderLeave = leaveRequests.save(new LeaveRequest(company.getId(), outsider.getId(),
                LeaveType.CASUAL, attendanceDate, attendanceDate, BigDecimal.ONE, "unrelated request"));

        String managerToken = login(managerAccount.getEmail(), "ManagerPassword123", "192.0.2.31");
        String employeeToken = login(employeeAccount.getEmail(), "EmployeePassword123", "192.0.2.32");
        String hrToken = login(hrAccount.getEmail(), "HrPassword123", "192.0.2.33");
        String adminToken = login(adminAccount.getEmail(), "AdminPassword123", "192.0.2.34");

        mockMvc.perform(get("/api/v1/employees/{id}", report.getId()).header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/employees/{id}", outsider.getId()).header("Authorization", bearer(managerToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/employees").param("search", "O-")
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.content", org.hamcrest.Matchers.empty()));

        mockMvc.perform(get("/api/v1/leaves/employee/{id}", report.getId())
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/leaves/employee/{id}", outsider.getId())
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/leaves/{id}/approve", outsiderLeave.getId())
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/leaves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "employeeId", outsider.getId(),
                                "leaveType", LeaveType.CASUAL.name(),
                                "startDate", attendanceDate.plusDays(2).toString(),
                                "endDate", attendanceDate.plusDays(2).toString(),
                                "reason", "unauthorized")))
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isNotFound());
        assertEquals(LeaveStatus.PENDING,
                leaveRequests.findByIdAndCompanyId(outsiderLeave.getId(), company.getId()).orElseThrow().getStatus());
        assertEquals(LeaveStatus.PENDING,
                leaveRequests.findByIdAndCompanyId(reportLeave.getId(), company.getId()).orElseThrow().getStatus());

        mockMvc.perform(get("/api/v1/attendance/daily")
                        .param("date", attendanceDate.toString())
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.length()", org.hamcrest.Matchers.is(1)));
        mockMvc.perform(get("/api/v1/attendance/employee")
                        .param("employeeId", outsider.getId().toString())
                        .param("from", attendanceDate.toString())
                        .param("to", attendanceDate.toString())
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/attendance/summary/monthly")
                        .param("employeeId", outsider.getId().toString())
                        .param("year", Integer.toString(attendanceDate.getYear()))
                        .param("month", Integer.toString(attendanceDate.getMonthValue()))
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/attendance/check-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "employeeId", outsider.getId())))
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/attendance/check-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "employeeId", outsider.getId())))
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/employees/me")
                        .header("Authorization", bearer(employeeToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/employees/{id}", outsider.getId())
                        .header("Authorization", bearer(employeeToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/leaves/employee/{id}", outsider.getId())
                        .header("Authorization", bearer(employeeToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/attendance/employee")
                        .param("employeeId", outsider.getId().toString())
                        .param("from", attendanceDate.toString())
                        .param("to", attendanceDate.toString())
                        .header("Authorization", bearer(employeeToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/leaves/mine").header("Authorization", bearer(employeeToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/attendance/mine")
                        .param("from", attendanceDate.toString())
                        .param("to", attendanceDate.toString())
                        .header("Authorization", bearer(employeeToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/employees/{id}", outsider.getId())
                        .header("Authorization", bearer(hrToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/leaves/employee/{id}", outsider.getId())
                        .header("Authorization", bearer(hrToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/attendance/employee")
                        .param("employeeId", outsider.getId().toString())
                        .param("from", attendanceDate.toString())
                        .param("to", attendanceDate.toString())
                        .header("Authorization", bearer(hrToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/employees/{id}", outsider.getId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/leaves/employee/{id}", outsider.getId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/attendance/employee")
                        .param("employeeId", outsider.getId().toString())
                        .param("from", attendanceDate.toString())
                        .param("to", attendanceDate.toString())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/employees/{id}", otherCompanyEmployee.getId())
                        .header("Authorization", bearer(hrToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deactivatingEmployeeRevokesJwtAndReactivationDoesNotRestoreAccount() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Company company = company(suffix);
        Employee employee = employeeRepository.save(employee(company.getId(), "E-" + suffix, null));
        UserAccount employeeAccount = account(company.getId(), employee.getId(), UserRole.EMPLOYEE,
                "employee-deactivate-" + suffix + "@example.com", "EmployeePassword123");
        UserAccount hrAccount = account(company.getId(), null, UserRole.HR,
                "hr-deactivate-" + suffix + "@example.com", "HrPassword123");
        String employeeToken = login(employeeAccount.getEmail(), "EmployeePassword123", "192.0.2.41");
        String hrToken = login(hrAccount.getEmail(), "HrPassword123", "192.0.2.42");

        mockMvc.perform(patch("/api/v1/employees/{id}/status", employee.getId())
                        .param("status", EmployeeStatus.INACTIVE.name())
                        .header("Authorization", bearer(hrToken)))
                .andExpect(status().isOk());
        UserAccount deactivated = users.findById(employeeAccount.getId()).orElseThrow();
        assertFalse(deactivated.isActive());
        assertEquals(employeeAccount.getTokenVersion() + 1, deactivated.getTokenVersion());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(employeeToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").with(remoteAddress("192.0.2.41"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(
                                employeeAccount.getEmail(), "EmployeePassword123"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/v1/employees/{id}/status", employee.getId())
                        .param("status", EmployeeStatus.ACTIVE.name())
                        .header("Authorization", bearer(hrToken)))
                .andExpect(status().isOk());
        assertFalse(users.findById(employeeAccount.getId()).orElseThrow().isActive());
    }

    private UserAccount account(UUID companyId, UUID employeeId, UserRole role, String email, String password) {
        UserAccount account = new UserAccount(companyId, email, passwordEncoder.encode(password), role);
        account.setEmployeeId(employeeId);
        return users.save(account);
    }

    private Company company(String suffix) {
        return companies.save(new Company("Test Co " + suffix, "company-" + suffix + "@example.com", null, null));
    }

    private Employee employee(UUID companyId, String code, UUID managerId) {
        return new Employee(companyId, code, "First", "Last", code.toLowerCase() + "@example.com",
                null, null, managerId, null, LocalDate.now().minusYears(1));
    }

    private String login(String email, String password, String remoteAddress) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login").with(remoteAddress(remoteAddress))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    private RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String companyRequest() throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "companyName", "Private Registration",
                "companyEmail", "private-" + UUID.randomUUID() + "@example.com",
                "adminName", "Company Admin",
                "adminEmail", "admin-" + UUID.randomUUID() + "@example.com"));
    }
}
