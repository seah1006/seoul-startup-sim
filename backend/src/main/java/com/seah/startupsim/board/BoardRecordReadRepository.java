package com.seah.startupsim.board;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * RECORD 인증글용 읽기 전용 조회. 게임 소유·상태는 games, 스냅샷은 엔딩 요약(GET /api/games/{id}/summary)과
 * 같은 원본인 game_records에서 읽는다. 쓰기는 하지 않는다.
 */
@Repository
public class BoardRecordReadRepository {
    record GameState(long userId, String status) {}

    private final JdbcTemplate jdbc;

    public BoardRecordReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<GameState> game(long gameId) {
        return jdbc.query("SELECT user_id, status FROM games WHERE id = ?",
                (rs, n) -> new GameState(rs.getLong("user_id"), rs.getString("status")), gameId).stream().findFirst();
    }

    Optional<RecordSnapshot> snapshot(long gameId) {
        return jdbc.query("""
                SELECT game_id, ending_type, service_code, service_name, area_code, area_name,
                       survived_quarters, total_profit, shop_name, owner_name
                FROM game_records WHERE game_id = ?
                """, (rs, n) -> new RecordSnapshot(rs.getLong("game_id"), rs.getString("ending_type"),
                rs.getString("service_code"), rs.getString("service_name"), rs.getString("area_code"),
                rs.getString("area_name"), rs.getInt("survived_quarters"), rs.getLong("total_profit"),
                rs.getString("shop_name"), rs.getString("owner_name"), "/share/" + rs.getLong("game_id")), gameId)
                .stream().findFirst();
    }

    Optional<RecordPostLink> publicLink(long gameId) {
        return jdbc.query("""
                SELECT id, title, comment_count FROM posts
                WHERE game_id = ? AND category = 'RECORD' AND status = 'ACTIVE'
                """, (rs, n) -> new RecordPostLink(rs.getLong("id"), rs.getString("title"),
                rs.getInt("comment_count"), "/board/posts/" + rs.getLong("id")), gameId).stream().findFirst();
    }
}
