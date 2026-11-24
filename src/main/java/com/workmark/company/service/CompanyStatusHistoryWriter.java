package com.workmark.company.service;

import com.workmark.company.entity.CompanyStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class CompanyStatusHistoryWriter {
    private final JdbcTemplate jdbc;

    public CompanyStatusHistoryWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID companyId, UUID actorId, CompanyStatus previous, CompanyStatus current) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO company_status_history
                    (id, company_id, actor_id, previous_status, new_status, changed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), companyId, actorId, previous.name(), current.name(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }
}
