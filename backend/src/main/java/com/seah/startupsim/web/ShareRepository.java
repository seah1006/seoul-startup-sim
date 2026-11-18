package com.seah.startupsim.web;

import com.seah.startupsim.game.GameRecordRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 공유 페이지는 공개 가능한 엔딩 기록 필드만 읽는다. 잔액·결정 기록·토큰은 조회하지 않는다. */
@Repository
public class ShareRepository {
    public record Ending(long gameId, String endingType, String nickname, String ownerName, String shopName,
            String areaName, String serviceName, int survivedQuarters, long totalProfit, long bestQuarterProfit,
            int shopCount) {}

    private final JdbcTemplate jdbc;

    public ShareRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Ending ending(long gameId) {
        var rows = jdbc.query("""
                SELECT r.game_id, r.ending_type, %s AS nickname, r.owner_name, r.shop_name, r.area_name,
                       r.service_name, r.survived_quarters, r.total_profit, r.best_quarter_profit, r.shop_count
                FROM game_records r JOIN users u ON u.id = r.user_id
                WHERE r.game_id = ?
                """.formatted(GameRecordRepository.DISPLAY_NICKNAME), (row, index) -> new Ending(row.getLong("game_id"),
                row.getString("ending_type"), row.getString("nickname"), row.getString("owner_name"),
                row.getString("shop_name"), row.getString("area_name"), row.getString("service_name"),
                row.getInt("survived_quarters"), row.getLong("total_profit"), row.getLong("best_quarter_profit"),
                row.getInt("shop_count")), gameId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
