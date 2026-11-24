package com.workmark.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresMigrationPreflight {
    private final JdbcTemplate jdbc;

    public PostgresMigrationPreflight(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void verifyTenantMigrationPrerequisites() {
        Boolean extensionInstalled = jdbc.queryForObject(
                "select exists(select 1 from pg_extension where extname = 'btree_gist')",
                Boolean.class);
        if (!Boolean.TRUE.equals(extensionInstalled)) {
            throw new IllegalStateException(
                    "PostgreSQL prerequisite missing: CREATE EXTENSION btree_gist must be applied "
                            + "by a database administrator before WorkMark migrations");
        }
    }
}
