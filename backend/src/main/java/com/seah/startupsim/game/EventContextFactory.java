package com.seah.startupsim.game;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 결산 후 상태와 게임에 고정된 참조 버전으로 조건 변수만 구성한다. 결측은 넣지 않는다. */
@Component
public class EventContextFactory {
    private final GameRepository games;
    private final TurnRepository turns;
    private final JdbcTemplate jdbc;

    public EventContextFactory(GameRepository games, TurnRepository turns, JdbcTemplate jdbc) {
        this.games = games;
        this.turns = turns;
        this.jdbc = jdbc;
    }

    public Map<String, Object> variables(GameContext context, QuarterOutcome outcome) {
        long id = context.gameId();
        Game game = games.game(id, context.userId(), false);
        Shop shop = games.shop(id);
        var decision = turns.decision(id, context.turn());
        Map<String, Object> values = new HashMap<>();
        games.stats(id).forEach((stat, value) -> values.put("stat." + stat, value));
        values.put("quartersOperated", context.turn() + 1);
        values.put("negativeQuarters", game.negativeQuarters());
        values.put("quarterOfYear", Integer.parseInt(context.quarter().substring(4)));
        values.put("cash", game.cash());
        values.put("cumulativeProfit", jdbc.queryForObject("SELECT coalesce(sum(profit), 0) FROM turn_results WHERE game_id = ?", Long.class, id));
        values.put("lastProfit", outcome.profit());
        values.put("lastRevenue", outcome.revenue());
        values.put("areaRank", outcome.areaRank().rank());
        values.put("areaRankTotal", outcome.areaRank().of());
        values.put("areaRankPct", outcome.areaRank().rank() / (double) outcome.areaRank().of());
        values.put("staff", decision.staff());
        values.put("openHours", decision.openHours());
        values.put("marketingBudget", decision.marketingBudget());
        values.put("priceLevel", decision.priceLevel().name());
        values.put("serviceCode", shop.serviceCode());
        values.put("rivalCount", shop.rivalCount());
        values.put("shopCount", jdbc.queryForObject("SELECT shop_count FROM games WHERE id = ?", Integer.class, id));
        jdbc.query("SELECT category FROM service_industries WHERE service_code = ?", row -> {
            values.put("industryCategory", row.getString(1));
        }, shop.serviceCode());
        jdbc.query("SELECT area_type_name FROM trade_areas WHERE area_code = ?", row -> {
            put(values, "area.areaType", row.getObject(1));
        }, shop.areaCode());
        jdbc.query("""
                SELECT q.store_count, q.franchise_count, q.opening_count, q.closing_count,
                       q.opening_rate, q.closing_rate,
                       q.closing_rate - previous.closing_rate AS closing_delta
                FROM area_stores_q q
                LEFT JOIN area_stores_q previous ON previous.area_code = q.area_code
                    AND previous.service_code = q.service_code AND previous.quarter = ?
                    AND EXISTS (SELECT 1 FROM game_data_pins p WHERE p.game_id = ? AND p.version_id = previous.version_id)
                WHERE q.area_code = ? AND q.service_code = ? AND q.quarter = ?
                    AND EXISTS (SELECT 1 FROM game_data_pins p WHERE p.game_id = ? AND p.version_id = q.version_id)
                """, row -> {
                    String[] columns = {"store_count", "franchise_count", "opening_count", "closing_count", "opening_rate", "closing_rate", "closing_delta"};
                    String[] names = {"storeCount", "franchiseCount", "openingCount", "closingCount", "openingRate", "closingRate", "closingRateDelta"};
                    for (int index = 0; index < columns.length; index++) {
                        put(values, "area." + names[index], row.getObject(columns[index]));
                    }
                }, GameRepository.quarter(context.quarter(), -1), id, shop.areaCode(), shop.serviceCode(), context.quarter(), id);
        jdbc.query("""
                SELECT current.sales_amount, previous.sales_amount FROM area_sales_q current
                JOIN area_sales_q previous ON previous.area_code = current.area_code
                    AND previous.service_code = current.service_code AND previous.quarter = ?
                WHERE current.area_code = ? AND current.service_code = ? AND current.quarter = ?
                  AND EXISTS (SELECT 1 FROM game_data_pins p WHERE p.game_id = ? AND p.version_id = current.version_id)
                  AND EXISTS (SELECT 1 FROM game_data_pins p WHERE p.game_id = ? AND p.version_id = previous.version_id)
                """, row -> {
                    Number current = (Number) row.getObject(1);
                    Number previous = (Number) row.getObject(2);
                    if (current != null && previous != null && current.doubleValue() > 0 && previous.doubleValue() > 0) {
                        values.put("area.salesTrendPct", current.doubleValue() / previous.doubleValue() - 1);
                    }
                }, GameRepository.quarter(context.quarter(), -4), shop.areaCode(), shop.serviceCode(), context.quarter(), id, id);
        dataVariables(id, shop, context.quarter(), values);
        jdbc.query("SELECT flag FROM game_flags WHERE game_id = ?", row -> {
            values.put("flag." + row.getString(1), true);
        }, id);
        jdbc.query("""
                SELECT p.code, count(*) AS times, max(g.turn) AS last_turn
                FROM game_turning_points g JOIN turning_points p ON p.id = g.turning_point_id
                WHERE g.game_id = ? GROUP BY p.code
                """, row -> {
                    values.put("history.timesFired." + row.getString("code"), row.getInt("times"));
                    values.put("history.quartersSince." + row.getString("code"), context.turn() - row.getInt("last_turn"));
                }, id);
        return values;
    }

    /** 원본 두 분기가 모두 게임 pins에 있을 때만 실제 변화율을 제공한다. 추정 경로는 사용하지 않는다. */
    void dataVariables(long gameId, Shop shop, String quarter, Map<String, Object> values) {
        values.put("area.quarterLabel", quarter.substring(0, 4) + "년 " + quarter.substring(4) + "분기");
        String previous = GameRepository.quarter(quarter, -1);
        jdbc.query("""
                SELECT c.store_count - p.store_count AS store_delta,
                       c.franchise_count - p.franchise_count AS franchise_delta,
                       c.opening_count, c.closing_count, c.opening_rate, c.closing_rate
                FROM area_stores_q c LEFT JOIN area_stores_q p
                  ON p.area_code = c.area_code AND p.service_code = c.service_code AND p.quarter = ?
                 AND EXISTS (SELECT 1 FROM game_data_pins g WHERE g.game_id = ? AND g.version_id = p.version_id)
                WHERE c.area_code = ? AND c.service_code = ? AND c.quarter = ?
                  AND EXISTS (SELECT 1 FROM game_data_pins g WHERE g.game_id = ? AND g.version_id = c.version_id)
                """, row -> {
                    put(values, "area.storeChange", row.getObject("store_delta"));
                    put(values, "area.franchiseChange", row.getObject("franchise_delta"));
                    put(values, "area.openings", row.getObject("opening_count"));
                    put(values, "area.closings", row.getObject("closing_count"));
                    put(values, "area.openingRatePct", row.getObject("opening_rate"));
                    put(values, "area.closingRatePct", row.getObject("closing_rate"));
                }, previous, gameId, shop.areaCode(), shop.serviceCode(), quarter, gameId);
        jdbc.query("""
                SELECT c.sales_amount, p.sales_amount FROM area_sales_q c JOIN area_sales_q p
                  ON p.area_code = c.area_code AND p.service_code = c.service_code AND p.quarter = ?
                WHERE c.area_code = ? AND c.service_code = ? AND c.quarter = ?
                  AND EXISTS (SELECT 1 FROM game_data_pins g WHERE g.game_id = ? AND g.version_id = c.version_id)
                  AND EXISTS (SELECT 1 FROM game_data_pins g WHERE g.game_id = ? AND g.version_id = p.version_id)
                """, row -> { change(values, "area.salesChangePct", (Number) row.getObject(1), (Number) row.getObject(2)); },
                previous, shop.areaCode(), shop.serviceCode(), quarter, gameId, gameId);
        jdbc.query("""
                SELECT c.total_population, p.total_population FROM area_floating_pop_q c JOIN area_floating_pop_q p
                  ON p.area_code = c.area_code AND p.quarter = ?
                WHERE c.area_code = ? AND c.quarter = ?
                  AND EXISTS (SELECT 1 FROM game_data_pins g WHERE g.game_id = ? AND g.version_id = c.version_id)
                  AND EXISTS (SELECT 1 FROM game_data_pins g WHERE g.game_id = ? AND g.version_id = p.version_id)
                """, row -> { change(values, "area.floatingPopChangePct", (Number) row.getObject(1), (Number) row.getObject(2)); },
                previous, shop.areaCode(), quarter, gameId, gameId);
    }

    static void change(Map<String, Object> values, String name, Number current, Number previous) {
        if (current != null && previous != null && previous.doubleValue() > 0 && current.doubleValue() >= 0) {
            values.put(name, (current.doubleValue() / previous.doubleValue() - 1) * 100);
        }
    }
    /** 반응 전용 버킷과 마지막 해결 전환점을 기본 변수 위에 더한다. 직전 분기가 없으면 보합 기준이다. */
    public Map<String, Object> reactionVariables(GameContext context, QuarterOutcome outcome, Map<String, Object> base) {
        long id = context.gameId();
        Map<String, Object> values = new HashMap<>(base);
        var previous = jdbc.query("""
                SELECT profit, visitors, area_rank FROM turn_results WHERE game_id = ? AND turn = ?
                """, (row, index) -> new long[] {row.getLong("profit"), row.getLong("visitors"), row.getLong("area_rank")},
                id, context.turn() - 1);
        long[] last = previous.isEmpty() ? null : previous.getFirst();
        values.put("bucket.profit", outcome.profit() < 0 ? "LOSS" : last == null ? "FLAT" : trend(outcome.profit(), last[0]));
        values.put("bucket.visitors", last == null ? "FLAT" : trend(outcome.visitors(), last[1]));
        values.put("bucket.rank", last == null || outcome.areaRank().rank() == last[2] ? "SAME"
                : outcome.areaRank().rank() < last[2] ? "UP" : "DOWN");
        values.put("bucket.price", base.get("priceLevel"));
        int hours = ((Number) base.get("openHours")).intValue();
        values.put("bucket.hours", hours <= 8 ? "SHORT" : hours >= 13 ? "LONG" : "NORMAL");
        values.put("bucket.firstQuarter", context.turn() == 0);
        values.put("lastResolved.code", "NONE");
        values.put("lastResolved.result", "NONE");
        values.put("lastResolved.quartersSince", 999);
        jdbc.query("""
                SELECT p.code, g.success, g.turn FROM game_turning_points g
                JOIN turning_points p ON p.id = g.turning_point_id
                WHERE g.game_id = ? AND g.resolved_at IS NOT NULL
                ORDER BY g.turn DESC, g.id DESC LIMIT 1
                """, row -> {
                    values.put("lastResolved.code", row.getString("code"));
                    values.put("lastResolved.result", row.getBoolean("success") ? "SUCCESS" : "FAIL");
                    values.put("lastResolved.quartersSince", context.turn() - row.getInt("turn"));
                }, id);
        return values;
    }

    /** 변화가 10% 이상이면 UP/DOWN, 그 외는 FLAT. */
    private static String trend(long current, long previous) {
        long difference = current - previous;
        double threshold = Math.abs(previous) * 0.1;
        if (difference > 0 && difference >= threshold) {
            return "UP";
        }
        if (difference < 0 && -difference >= threshold) {
            return "DOWN";
        }
        return "FLAT";
    }

    /** 조건이 읽는 플래그·이력 변수의 기본값을 채운다. 다른 결측은 그대로 둠다. */
    static void defaults(JsonNode node, Map<String, Object> variables) {
        if (node.isObject() && node.has("var")) {
            String name = node.get("var").asString();
            if (name.startsWith("flag.")) {
                variables.putIfAbsent(name, false);
            } else if (name.startsWith("history.timesFired.")) {
                variables.putIfAbsent(name, 0);
            } else if (name.startsWith("history.quartersSince.")) {
                variables.putIfAbsent(name, 999);
            }
        }
        for (JsonNode child : node) {
            if (child.isContainer()) {
                defaults(child, variables);
            }
        }
    }

    private void put(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }
}
