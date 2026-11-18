package com.seah.startupsim.game;

import com.seah.startupsim.game.GameResponses.Guest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 토큰 원문은 발급 응답에만 담고 SHA-256 해시를 저장해 DB 유출 시 노출을 줄인다. */
@Component
public class GuestAuth {
    private final JdbcTemplate jdbc;

    public GuestAuth(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Guest create() {
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        String nickname = "손님-" + UUID.randomUUID().toString().substring(0, 4);
        Long id = jdbc.queryForObject("""
                INSERT INTO users (nickname, guest_token_hash) VALUES (?, ?) RETURNING id
                """, Long.class, nickname, hash(token));
        return new Guest(token, id, nickname);
    }

    public long resolve(String token) {
        if (token == null || token.isBlank()) {
            throw new GameException(401, "UNAUTHORIZED", "게스트 인증이 필요합니다.");
        }
        var ids = jdbc.queryForList("""
                SELECT id FROM users WHERE guest_token_hash = ?
                """, Long.class, hash(token));
        if (ids.isEmpty()) {
            throw new GameException(401, "UNAUTHORIZED", "게스트 인증이 필요합니다.");
        }
        return ids.getFirst();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
