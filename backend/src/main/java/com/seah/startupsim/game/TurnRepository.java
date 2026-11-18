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
                SET response = ?::jsonb, reactions = ?::jsonb, moment = ?::jsonb
                WHERE game_id = ? AND turn = ?
                """, json.writeValueAsString(outcome), json.writeValueAsString(outcome.reactions()),
                outcome.moment() == null ? null : json.writeValueAsString(outcome.moment()), gameId, outcome.turn());
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
        return cached(gameId, key, "advance", GameResponses.Advanced.class);
    }

    /** 응답 역직렬화 전에 엔드포인트를 검사해 서로 다른 응답 계약이 섞이지 않게 한다. */
    public <T> T cached(long gameId, String key, String endpoint, Class<T> responseType) {
        var responses = jdbc.query("""
                SELECT endpoint, response::text FROM idempotency_keys WHERE game_id = ? AND key = ?
                """, (row, index) -> new CachedResponse(row.getString("endpoint"), row.getString("response")), gameId, key);
        if (responses.isEmpty()) {
            return null;
        }
        CachedResponse response = responses.getFirst();
        if (!response.endpoint().equals(endpoint)) {
            throw new GameException(409, "IDEMPOTENCY_KEY_REUSED", "다른 요청에 사용한 멱등 키입니다.");
        }
        return json.readValue(response.json(), responseType);
    }

    public GameResponses.Advanced cache(long gameId, String key, GameResponses.Advanced response) {
        return cache(gameId, key, "advance", response, GameResponses.Advanced.class);
    }

    public <T> T cache(long gameId, String key, String endpoint, T response, Class<T> responseType) {
        String encoded = json.writeValueAsString(response);
        jdbc.update("""
                INSERT INTO idempotency_keys (game_id, key, endpoint, response) VALUES (?, ?, ?, ?::jsonb)
                """, gameId, key, endpoint, encoded);
        return json.readValue(encoded, responseType);
    }

    private record CachedResponse(String endpoint, String json) {}
}
