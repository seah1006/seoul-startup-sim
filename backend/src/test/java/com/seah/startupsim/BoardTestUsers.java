package com.seah.startupsim;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

import com.seah.startupsim.auth.AuthService;

/** 게시판 테스트용 회원·게스트 사용자와 세션 토큰을 DB에 직접 만든다. */
final class BoardTestUsers {
    record TestUser(long id, String token) {}

    private BoardTestUsers() {}

    static TestUser member(JdbcTemplate jdbc, String nickname) {
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long id = jdbc.queryForObject("""
                INSERT INTO users(nickname, role, is_guest, login_id, password_hash)
                VALUES (?, 'MEMBER', false, ?, 'x') RETURNING id
                """, Long.class, nickname, login);
        return new TestUser(id, session(jdbc, id, "MEMBER"));
    }

    static TestUser guest(JdbcTemplate jdbc, String nickname) {
        long id = jdbc.queryForObject("INSERT INTO users(nickname) VALUES (?) RETURNING id", Long.class, nickname);
        return new TestUser(id, session(jdbc, id, "GUEST"));
    }

    private static String session(JdbcTemplate jdbc, long userId, String kind) {
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        jdbc.update("INSERT INTO auth_sessions(user_id, token_hash, kind, expires_at) VALUES (?, ?, ?, now() + interval '14 days')",
                userId, AuthService.hash(token), kind);
        return token;
    }
}
