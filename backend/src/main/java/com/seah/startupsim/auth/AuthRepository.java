package com.seah.startupsim.auth;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 사용자와 토큰 해시의 영속화를 담당한다. */
@Repository
public class AuthRepository {
    final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Account(long id, String loginId, String nickname, String role,
            String passwordHash, Instant createdAt, Instant lockedUntil, Instant deletedAt) {
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public List<Account> find(String condition, Object... args) {
        return jdbc.query("SELECT * FROM users WHERE " + condition, (rs, row) -> new Account(
                rs.getLong("id"), rs.getString("login_id"), rs.getString("nickname"), rs.getString("role"),
                rs.getString("password_hash"), rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("locked_until")), instant(rs.getTimestamp("deleted_at"))), args);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

