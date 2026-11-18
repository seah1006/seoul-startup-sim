package com.seah.startupsim.ranking;

import com.seah.startupsim.game.GameRecordRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 스냅샷 순위와 엔딩 기록을 한 번에 조인해 읽는다. 탈퇴 사용자는 표시 시점에 익명화한다. */
@Repository
public class RankingRepository {
    public record Entry(int rank, long gameId, String nickname, String ownerName, String shopName, String areaName,
            String serviceName, String endingType, int survivedQuarters, long totalProfit) {}
    public record DateCount(LocalDate date, int records, Instant refreshedAt) {}

    private final JdbcTemplate jdbc;

    public RankingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Entry> entries(LocalDate date, RankingKind kind, String key, int limit) {
        return jdbc.query("""
                SELECT s.rank, r.game_id, %s AS nickname, r.owner_name, r.shop_name, r.area_name, r.service_name,
                       r.ending_type, r.survived_quarters, r.total_profit
                FROM ranking_snapshots s
                JOIN game_records r ON r.game_id = s.game_id
                JOIN users u ON u.id = r.user_id
                WHERE s.ranking_date = ? AND s.kind = ? AND s.key = ?
                ORDER BY s.rank, s.game_id
                LIMIT ?
                """.formatted(GameRecordRepository.DISPLAY_NICKNAME), (row, index) -> new Entry(row.getInt("rank"),
                row.getLong("game_id"), row.getString("nickname"), row.getString("owner_name"),
                row.getString("shop_name"), row.getString("area_name"), row.getString("service_name"),
                row.getString("ending_type"), row.getInt("survived_quarters"), row.getLong("total_profit")),
                Date.valueOf(date), kind.name(), key, limit);
    }

    public Instant refreshedAt(LocalDate date) {
        Timestamp value = jdbc.queryForObject("""
                SELECT max(refreshed_at) FROM ranking_snapshots WHERE ranking_date = ?
                """, Timestamp.class, Date.valueOf(date));
        return value == null ? null : value.toInstant();
    }

    /** 관리자 화면용: 날짜별 엔딩 기록 수, 스냅샷 행 수, 갱신 시각. */
    public List<DateCount> recent(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT d::date AS day,
                       (SELECT count(*) FROM game_records r WHERE r.ranking_date = d::date) AS records,
                       (SELECT max(refreshed_at) FROM ranking_snapshots s WHERE s.ranking_date = d::date) AS refreshed_at
                FROM generate_series(?::date, ?::date, interval '1 day') d
                ORDER BY day DESC
                """, (row, index) -> new DateCount(row.getDate("day").toLocalDate(), row.getInt("records"),
                row.getTimestamp("refreshed_at") == null ? null : row.getTimestamp("refreshed_at").toInstant()),
                Date.valueOf(from), Date.valueOf(to));
    }

    public Instant lastRefreshedAt() {
        Timestamp value = jdbc.queryForObject("SELECT max(refreshed_at) FROM ranking_snapshots", Timestamp.class);
        return value == null ? null : value.toInstant();
    }
}
