package com.seah.startupsim.auth;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 사용자와 토큰 해시의 영속화를 담당한다. */
@Repository
public class AuthRepository {
    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Account(long id, String loginId, String nickname, String role,
            String passwordHash, Instant createdAt, Instant lockedUntil, Instant deletedAt) {
    }


    private List<Account> find(String condition, Object... args) {
        return jdbc.query("SELECT * FROM users WHERE " + condition, (rs, row) -> new Account(
                rs.getLong("id"), rs.getString("login_id"), rs.getString("nickname"), rs.getString("role"),
                rs.getString("password_hash"), rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("locked_until")), instant(rs.getTimestamp("deleted_at"))), args);
    }

    public List<Account> findById(long id, boolean lock) {
        return find("id = ? AND deleted_at IS NULL" + (lock ? " FOR UPDATE" : ""), id);
    }

    public List<Account> findByLoginForUpdate(String loginId) {
        return find("login_id = ? FOR UPDATE", loginId);
    }

    public long createGuest(String nickname) {
        return jdbc.queryForObject("""
                INSERT INTO users(nickname, role) VALUES (?, 'GUEST') RETURNING id
                """, Long.class, nickname);
    }

    public void createSession(long userId, String tokenHash, String kind, Timestamp now, Timestamp expires) {
        jdbc.update("""
                INSERT INTO auth_sessions(user_id, token_hash, kind, created_at, last_used_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, tokenHash, kind, now, now, expires);
    }

    public List<AuthenticatedUser> authenticate(String tokenHash, Timestamp now, Timestamp extended) {
        return jdbc.query("""
                UPDATE auth_sessions s SET last_used_at = ?,
                    expires_at = CASE WHEN s.kind = 'MEMBER' THEN ? ELSE s.expires_at END
                FROM users u WHERE s.user_id = u.id AND s.token_hash = ?
                    AND s.revoked_at IS NULL AND s.expires_at > ? AND u.deleted_at IS NULL
                RETURNING u.id, u.role
                """, (rs, row) -> new AuthenticatedUser(rs.getLong(1), rs.getString(2), tokenHash), now, extended, tokenHash, now);
    }

    public void convert(long id, String loginId, String passwordHash, String nickname, Timestamp now) {
        jdbc.update("""
                        UPDATE users SET login_id = ?, password_hash = ?, nickname = ?, role = 'MEMBER',
                            is_guest = false, updated_at = ? WHERE id = ?
                        """, loginId, passwordHash, nickname, now, id);
    }

    public long createMember(String loginId, String passwordHash, String nickname) {
        return jdbc.queryForObject("""
                        INSERT INTO users(login_id, password_hash, nickname, role, is_guest)
                        VALUES (?, ?, ?, 'MEMBER', false) RETURNING id
                        """, Long.class, loginId, passwordHash, nickname);
    }

    public void countLockedAttempt(long id) {
        jdbc.update("UPDATE users SET failed_login_count = failed_login_count + 1 WHERE id = ?", id);
    }

    public int failLogin(long id, Timestamp cutoff, Timestamp now) {
        return jdbc.queryForObject("""
                    UPDATE users SET failed_login_count = CASE
                        WHEN failed_login_at IS NULL OR failed_login_at <= ? THEN 1 ELSE failed_login_count + 1 END,
                        failed_login_at = CASE WHEN failed_login_at IS NULL OR failed_login_at <= ? THEN ? ELSE failed_login_at END,
                        updated_at = ? WHERE id = ? RETURNING failed_login_count
                    """, Integer.class, cutoff, cutoff, now, now, id);
    }

    public void lock(long id, Timestamp until) {
        jdbc.update("UPDATE users SET locked_until = ? WHERE id = ?", until, id);
    }

    public void clearFailures(long id) {
        jdbc.update("""
                UPDATE users SET failed_login_count = 0, failed_login_at = NULL, locked_until = NULL WHERE id = ?
                """, id);
    }

    public void revoke(String tokenHash, Timestamp now) {
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE token_hash = ?", now, tokenHash);
    }

    public void rename(long id, String nickname, Timestamp now) {
        jdbc.update("UPDATE users SET nickname = ?, updated_at = ? WHERE id = ?", nickname, now, id);
    }

    public void changePassword(long id, String passwordHash, Timestamp now) {
        jdbc.update("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?", passwordHash, now, id);
    }

    public void delete(long id, Timestamp now) {
        jdbc.update("""
                UPDATE users SET deleted_at = ?, nickname = '탈퇴한 사장님', updated_at = ? WHERE id = ?
                """, now, now, id);
    }

    public void revokeAll(long id, String keep, Timestamp now) {
        jdbc.update("""
                UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL
                    AND (CAST(? AS text) IS NULL OR token_hash <> ?)
                """, now, id, keep, keep);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

