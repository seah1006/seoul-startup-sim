package com.seah.startupsim.game;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** 계산 결과와 이벤트 표현을 같은 행에 저장하며 분기 유일 제약을 유지한다. */
@Repository
public class TurnRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TurnRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void saveOutcome(long gameId, long marketSize, QuarterOutcome outcome) {
        jdbc.update("""
                INSERT INTO turn_results
                    (game_id, turn, market_size, share, revenue, visitors, cost, profit,
                     area_rank, stat_changes, response)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, gameId, outcome.turn(), marketSize,
                marketSize == 0 ? 0 : outcome.revenue() / (double) marketSize,
                outcome.revenue(), outcome.visitors(), outcome.costs().total(), outcome.profit(),
                outcome.areaRank().rank(), json.writeValueAsString(outcome.statChanges()),
                json.writeValueAsString(outcome));
    }

    public void saveEventPresentation(long gameId, QuarterOutcome outcome) {
        jdbc.update("""
                UPDATE turn_results
                SET response = ?::jsonb, reactions = ?::jsonb
                WHERE game_id = ? AND turn = ?
                """, json.writeValueAsString(outcome), json.writeValueAsString(outcome.reactions()),
                gameId, outcome.turn());
    }
}
