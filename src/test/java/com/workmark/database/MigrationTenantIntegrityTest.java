package com.workmark.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class MigrationTenantIntegrityTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine").withInitScript("postgres-test-init.sql");

    @Test
    void freshDatabaseMigratesToCurrentVersion() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("fresh_install")
                .load();
        flyway.migrate();

        assertNotNull(flyway.info().current());
        assertEquals(0, flyway.info().pending().length);
    }

    @Test
    void leaveApproverConstraintIsTenantScopedAndAllowsNull() {
        Flyway existingDatabase = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("11"))
                .load();
        existingDatabase.migrate();
        assertEquals(MigrationVersion.fromVersion("11"), existingDatabase.info().current().getVersion());

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        UUID companyA = UUID.randomUUID();
        UUID companyB = UUID.randomUUID();
        UUID employeeA = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID leaveA = UUID.randomUUID();

        jdbc.update("""
                insert into companies(id, company_name, email, status, created_at, updated_at)
                values (?, 'A', ?, 'ACTIVE', now(), now())
                """, companyA, "a-" + companyA + "@example.com");
        jdbc.update("""
                insert into companies(id, company_name, email, status, created_at, updated_at)
                values (?, 'B', ?, 'ACTIVE', now(), now())
                """, companyB, "b-" + companyB + "@example.com");
        jdbc.update("""
                insert into employees(id, company_id, employee_code, first_name, last_name, email,
                                      joining_date, status, created_at, updated_at)
                values (?, ?, 'E1', 'A', 'B', ?, current_date, 'ACTIVE', now(), now())
                """, employeeA, companyA, "employee-" + employeeA + "@example.com");
        jdbc.update("""
                insert into user_accounts(id, company_id, display_name, email, password_hash, role,
                                          active, token_version, created_at, updated_at)
                values (?, ?, 'A', ?, 'hash', 'HR', true, 0, now(), now())
                """, userA, companyA, "hr-" + userA + "@example.com");
        jdbc.update("""
                insert into user_accounts(id, company_id, display_name, email, password_hash, role,
                                          active, token_version, created_at, updated_at)
                values (?, ?, 'B', ?, 'hash', 'HR', true, 0, now(), now())
                """, userB, companyB, "hr-" + userB + "@example.com");
        jdbc.update("""
                insert into leave_requests(id, company_id, employee_id, leave_type, start_date, end_date,
                                           number_of_days, reason, status, approved_by, created_at, updated_at)
                values (?, ?, ?, 'CASUAL', current_date, current_date, 1, 'pending', 'PENDING', null, now(), now())
                """, leaveA, companyA, employeeA);

        Flyway upgradedDatabase = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load();
        upgradedDatabase.migrate();

        assertTrue(upgradedDatabase.info().current().getVersion().compareTo(MigrationVersion.fromVersion("11")) > 0);
        assertEquals(0, upgradedDatabase.info().pending().length);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM companies", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_requests WHERE id = ? AND approved_by IS NULL", Integer.class, leaveA));

        jdbc.update("""
                update leave_requests set approved_by = ?, status = 'APPROVED'
                where id = ? and company_id = ?
                """, userA, leaveA, companyA);

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        update leave_requests set approved_by = ?, status = 'APPROVED'
                        where id = ? and company_id = ?
                        """, userB, leaveA, companyA));
    }
}
