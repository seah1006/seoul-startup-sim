package com.seah.startupsim.game;

import com.seah.startupsim.game.GameTypes.Decision;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 게임 시작 시 고정한 버전만 읽어 원본 갱신이 진행 중 게임을 바꾸지 않게 한다. */
@Repository
public class MarketDataRepository {
    private final JdbcTemplate jdbc;
    private record StoreCounts(int stores, long openings, long closings) {}

    public MarketDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public QuarterSnapshot snapshot(Game game, Shop shop, Owner owner, Decision decision) {
        String quarter = GameRepository.quarter(game.startQuarter(), game.currentTurn());
        var sales = jdbc.queryForList("""
                SELECT q.sales_amount FROM area_sales_q q
                JOIN game_data_pins p ON p.version_id = q.version_id AND p.game_id = ?
                WHERE q.area_code = ? AND q.service_code = ? AND q.quarter = ?
                """, Long.class, game.id(), shop.areaCode(), shop.serviceCode(), quarter);
        var counts = jdbc.query("""
                SELECT q.store_count, q.opening_count, q.closing_count FROM area_stores_q q
                JOIN game_data_pins p ON p.version_id = q.version_id AND p.game_id = ?
                WHERE q.area_code = ? AND q.service_code = ? AND q.quarter = ?
                """, (row, index) -> new StoreCounts(row.getInt(1), row.getLong(2), row.getLong(3)),
                game.id(), shop.areaCode(), shop.serviceCode(), quarter);
        List<String> dataNotes = List.of();
        StoreCounts count;
        if (counts.isEmpty()) {
            var previous = jdbc.queryForList("""
                    SELECT q.store_count FROM area_stores_q q
                    JOIN game_data_pins p ON p.version_id = q.version_id AND p.game_id = ?
                    WHERE q.area_code = ? AND q.service_code = ? AND q.quarter < ?
                    ORDER BY q.quarter DESC LIMIT 1
                    """, Integer.class, game.id(), shop.areaCode(), shop.serviceCode(), quarter);
            int stores = previous.isEmpty() ? 1 : Math.max(1, previous.getFirst());
            count = new StoreCounts(stores, 0, 0);
            dataNotes = List.of(previous.isEmpty()
                    ? "이 분기의 점포 수 자료가 없어 기본 점포 수 1개로 추정했습니다."
                    : "이 분기의 점포 수 자료가 없어 직전 자료의 점포 수로 추정했습니다.");
        } else {
            count = counts.getFirst();
        }
        Long average = jdbc.queryForObject("""
                SELECT (sum(s.sales_amount) / nullif(sum(t.store_count), 0))::bigint
                FROM area_sales_q s JOIN area_stores_q t USING(area_code, quarter, service_code)
                JOIN trade_areas a ON a.area_code = s.area_code
                WHERE a.district_code = ? AND s.quarter = ? AND s.service_code = ?
                  AND EXISTS (SELECT 1 FROM game_data_pins p WHERE p.game_id = ? AND p.version_id = s.version_id)
                  AND EXISTS (SELECT 1 FROM game_data_pins p WHERE p.game_id = ? AND p.version_id = t.version_id)
                """, Long.class, shop.districtCode(), quarter, shop.serviceCode(), game.id(), game.id());
        Long market = sales.isEmpty() ? null : sales.getFirst();
        if (market == null && average == null) {
            throw GameException.invalid("고정된 버전에 이 분기의 매출 추정 자료가 없습니다.");
        }
        var input = new QuarterCalculator.Input(game.seed(), game.currentTurn(), quarter, game.cash(),
                market, average == null ? 0 : average, count.stores(), (int) shop.rivalCount(), shop.areaM2(),
                count.openings(), count.closings(), owner.stats(), owner.focusStats(), decision,
                game.settings(), shop.districtCode());
        return new QuarterSnapshot(input, dataNotes);
    }
}
