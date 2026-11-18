package com.seah.startupsim.game;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 시작을 포함하고 종료를 제외하는 기간과 고정 순서로 이번 결산의 활성 보정만 읽는다. */
@Repository
public class GameModifierRepository {
    private final JdbcTemplate jdbc;

    public GameModifierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<QuarterCalculator.Modifier> active(long gameId, int turn) {
        return jdbc.query("""
                SELECT target, mode, value FROM game_modifiers
                WHERE game_id = ? AND start_turn <= ? AND ? < end_turn
                ORDER BY target, mode, id
                """, (row, index) -> new QuarterCalculator.Modifier(row.getString("target"),
                row.getString("mode"), row.getDouble("value")), gameId, turn, turn);
    }
}
