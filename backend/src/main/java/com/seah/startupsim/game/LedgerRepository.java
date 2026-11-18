package com.seah.startupsim.game;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 매출·비용을 함께 기록해 현금과 원장 합계가 같은 트랜잭션에 묶이게 한다. */
@Repository
public class LedgerRepository {
    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordQuarter(long gameId, QuarterOutcome outcome) {
        jdbc.update("""
                INSERT INTO ledger_entries (game_id, turn, amount, type, memo)
                VALUES (?, ?, ?, 'REVENUE', '분기 매출'),
                       (?, ?, ?, 'COST', '분기 운영비')
                """, gameId, outcome.turn(), outcome.revenue(), gameId, outcome.turn(),
                -outcome.costs().total());
    }
}
