package com.workmark.auth.bootstrap;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Component
public class PostgresBootstrapLock {
    private static final long LOCK_KEY = 6288528922353146193L;
    private final JdbcTemplate jdbcTemplate;

    public PostgresBootstrapLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void acquire() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, LOCK_KEY);
                try (ResultSet ignored = statement.executeQuery()) {
                    if (!ignored.next()) throw new IllegalStateException("Failed to acquire bootstrap lock");
                }
            }
            return null;
        });
    }
}
