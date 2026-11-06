package com.seah.startupsim.game;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** 게스트 토큰 원문은 발급 응답에만 담고 SHA-256 해시로 인증한다. */
@Component
public class GuestAuth {
    private final JdbcTemplate jdbc;
    public GuestAuth(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Map<String,Object> create() {
        String token=UUID.randomUUID().toString()+UUID.randomUUID();
        String nickname="손님-"+UUID.randomUUID().toString().substring(0,4);
        Long id=jdbc.queryForObject("INSERT INTO users(nickname,guest_token_hash) VALUES (?,?) RETURNING id",Long.class,nickname,hash(token));
        return Map.of("guestToken",token,"userId",id,"nickname",nickname);
    }
    public long resolve(String token) {
        if(token==null || token.isBlank()) throw new GameException(401,"UNAUTHORIZED","게스트 인증이 필요합니다.");
        var ids=jdbc.queryForList("SELECT id FROM users WHERE guest_token_hash=?",Long.class,hash(token));
        if(ids.isEmpty()) throw new GameException(401,"UNAUTHORIZED","게스트 인증이 필요합니다.");
        return ids.getFirst();
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
