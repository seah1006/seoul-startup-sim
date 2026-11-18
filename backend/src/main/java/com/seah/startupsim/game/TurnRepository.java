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

    public GameTypes.Decision decision(long gameId, int turn) {
        var decisions = jdbc.query("""
                SELECT price_level, open_hours, staff, marketing, owner_activity
                FROM turn_decisions WHERE game_id = ? AND turn = ?
                """, (row, index) -> new GameTypes.Decision(GameTypes.Price.valueOf(row.getString("price_level")),
                row.getInt("open_hours"), row.getInt("staff"), row.getLong("marketing"),
                GameTypes.Activity.valueOf(row.getString("owner_activity"))), gameId, turn);
        if (decisions.isEmpty()) {
            throw GameException.invalid("이번 분기의 운영 결정을 저장해 주세요.");
        }
        return decisions.getFirst();
    }

    public GameResponses.Advanced cached(long gameId, String key) {
        var responses = jdbc.queryForList("""
                SELECT response::text FROM idempotency_keys WHERE game_id = ? AND key = ?
                """, String.class, gameId, key);
        return responses.isEmpty() ? null : json.readValue(responses.getFirst(), GameResponses.Advanced.class);
    }

    public GameResponses.Advanced cache(long gameId, String key, GameResponses.Advanced response) {
        String encoded = json.writeValueAsString(response);
        jdbc.update("""
                INSERT INTO idempotency_keys (game_id, key, response) VALUES (?, ?, ?::jsonb)
                """, gameId, key, encoded);
        return json.readValue(encoded, GameResponses.Advanced.class);
    }
}
