package com.seah.startupsim.game;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 엔딩 트랜잭션 안에서 랭킹·요약·인증글의 원본 행을 한 번만 남긴다(PK game_id). */
@Repository
public class GameRecordRepository {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 탈퇴 사용자는 스냅샷 닉네임 대신 이 이름으로 보인다(DESIGN 20장). */
    public static final String DELETED_NICKNAME = "탈퇴한 사장님";
    /** 읽기 쿼리에서 users를 u로 조인했을 때 표시 닉네임 식. */
    public static final String DISPLAY_NICKNAME =
            "CASE WHEN u.deleted_at IS NULL THEN r.nickname ELSE '" + DELETED_NICKNAME + "' END";

    public record Summary(long gameId, String endingType, String ownerName, String shopName, String areaName,
            String serviceName, int survivedQuarters, long totalProfit, long bestQuarterProfit, int shopCount,
            String shareUrl, Mission.Ending mission) {}

    private final JdbcTemplate jdbc;

    public GameRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static LocalDate rankingDate(Instant endedAt) {
        return endedAt.atZone(KST).toLocalDate();
    }

    public static String shareUrl(long gameId) {
        return "/share/" + gameId;
    }

    /** 닉네임·이름·업종·상권·매장 수는 엔딩 시점 값을 복사하고, 숫자는 결산 이력에서 계산한다. */
    public void insert(long gameId, String endingType, Long saleValue, Instant endedAt) {
        int inserted = jdbc.update("""
                INSERT INTO game_records
                    (game_id, user_id, nickname, ending_type, survived_quarters, total_profit, best_quarter_profit,
                     shop_count, sale_value, area_code, area_name, service_code, service_name, shop_name, owner_name,
                     ended_at, ranking_date, mission_code, mission_achieved, mission_achieved_turn)
                SELECT g.id, g.user_id, u.nickname, ?,
                       (SELECT count(*) FROM turn_results t WHERE t.game_id = g.id),
                       (SELECT coalesce(sum(t.profit), 0) FROM turn_results t WHERE t.game_id = g.id),
                       (SELECT coalesce(max(t.profit), 0) FROM turn_results t WHERE t.game_id = g.id),
                       g.shop_count, ?, s.area_code, s.area_name, s.service_code, s.service_name, s.name, o.name, ?, ?, g.mission_code, g.mission_achieved_turn IS NOT NULL, g.mission_achieved_turn
                FROM games g
                JOIN users u ON u.id = g.user_id
                JOIN shops s ON s.game_id = g.id
                JOIN owners o ON o.game_id = g.id
                WHERE g.id = ?
                """, endingType, saleValue, Timestamp.from(endedAt), Date.valueOf(rankingDate(endedAt)), gameId);
        if (inserted != 1) {
            throw new IllegalStateException("엔딩 기록을 만들 게임·가게 정보가 없습니다.");
        }
    }

    public Summary summary(long gameId) {
        var rows = jdbc.query("""
                SELECT game_id, ending_type, owner_name, shop_name, area_name, service_name, survived_quarters,
                       total_profit, best_quarter_profit, shop_count, mission_code, mission_achieved, mission_achieved_turn
                FROM game_records WHERE game_id = ?
                """, (row, index) -> new Summary(row.getLong("game_id"), row.getString("ending_type"),
                row.getString("owner_name"), row.getString("shop_name"), row.getString("area_name"),
                row.getString("service_name"), row.getInt("survived_quarters"), row.getLong("total_profit"),
                row.getLong("best_quarter_profit"), row.getInt("shop_count"), shareUrl(row.getLong("game_id")),
                MissionRepository.ending(row.getString("mission_code"), row.getBoolean("mission_achieved"), (Integer) row.getObject("mission_achieved_turn"))), gameId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
