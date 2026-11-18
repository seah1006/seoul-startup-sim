package com.seah.startupsim.game;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import com.seah.startupsim.game.GameResponses.ShopState;
import com.seah.startupsim.game.GameResponses.State;

/** 소유권과 행 잠금으로 같은 게임의 동시 변경을 직렬화한다. */
@Repository
public class GameRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public GameRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    String encode(Object value) {
        return json.writeValueAsString(value);
    }

    <T> T decode(String value, Class<T> type) {
        return json.readValue(value, type);
    }

    Map<String, Double> settings(String value) {
        Map<String, Double> settings = new LinkedHashMap<>();
        var tree = json.readTree(value);
        tree.properties().forEach(entry -> settings.put(entry.getKey(), entry.getValue().doubleValue()));
        return settings;
    }

    Game game(long id, long user, boolean lock) {
        var games = jdbc.query("""
                SELECT id, user_id, seed, start_quarter, last_quarter, current_turn, status,
                       ending_type, version, cash, initial_capital, negative_quarters, settings::text
                FROM games WHERE id = ?
                """ + (lock ? " FOR UPDATE" : ""), (row, index) -> new Game(row.getLong("id"),
                row.getLong("user_id"), row.getLong("seed"), row.getString("start_quarter"),
                row.getString("last_quarter"), row.getInt("current_turn"), row.getString("status"),
                row.getString("ending_type"), row.getLong("version"), row.getLong("cash"),
                row.getLong("initial_capital"), row.getInt("negative_quarters"),
                settings(row.getString("settings"))), id);
        if (games.isEmpty()) {
            throw new GameException(404, "NOT_FOUND", "게임을 찾을 수 없습니다.");
        }
        Game game = games.getFirst();
        if (game.userId() != user) {
            throw new GameException(403, "FORBIDDEN", "다른 게스트의 게임입니다.");
        }
        return game;
    }

    Owner owner(long id) {
        return jdbc.queryForObject("""
                SELECT name, background, cooking, service, management, marketing, stamina, network,
                       focus_stat_1, focus_stat_2 FROM owners WHERE game_id = ?
                """, (row, index) -> {
                    Map<String, Integer> stats = new LinkedHashMap<>();
                    for (String stat : OwnerCandidates.STATS) {
                        stats.put(stat, row.getInt(stat));
                    }
                    List<String> focus = Stream.of(row.getString("focus_stat_1"), row.getString("focus_stat_2"))
                            .filter(Objects::nonNull).toList();
                    return new Owner(row.getString("name"), row.getString("background"), stats, focus);
                }, id);
    }

    Map<String, Integer> stats(long id) {
        return owner(id).stats();
    }

    List<String> focus(long id) {
        return owner(id).focusStats();
    }

    Shop shop(long id) {
        var shops = jdbc.query("""
                SELECT area_code, area_name, district_code, service_code, service_name, name,
                       ST_X(geom) AS lng, ST_Y(geom) AS lat, area_m2,
                       (SELECT count(*) FROM rivals WHERE game_id = ?) AS rival_count
                FROM shops WHERE game_id = ?
                """, (row, index) -> new Shop(row.getString("area_code"), row.getString("area_name"),
                row.getString("district_code"), row.getString("service_code"), row.getString("service_name"),
                row.getString("name"), row.getDouble("lng"), row.getDouble("lat"), row.getLong("rival_count"),
                row.getObject("area_m2") == null ? 1 : row.getDouble("area_m2")), id, id);
        return shops.isEmpty() ? null : shops.getFirst();
    }

    public State state(long id, long user) {
        Game game = game(id, user, false);
        Shop shop = shop(id);
        ShopState shopState = shop == null ? null : new ShopState(shop.areaCode(), shop.areaName(),
                shop.serviceCode(), shop.serviceName(), shop.shopName(), shop.lng(), shop.lat(), shop.rivalCount());
        boolean pending = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT count(*) = 0 FROM turn_decisions WHERE game_id = ? AND turn = ?
                """, Boolean.class, id, game.currentTurn()));
        return new State(id, game.version(), game.status(), game.endingType(), game.currentTurn(),
                game.startQuarter(), game.lastQuarter(), quarter(game.startQuarter(), game.currentTurn()),
                game.cash(), owner(id), shopState, pending, false);
    }

    public List<QuarterOutcome> turns(long id, long user) {
        game(id, user, false);
        return jdbc.queryForList("""
                SELECT response::text FROM turn_results WHERE game_id = ? ORDER BY turn
                """, String.class, id).stream().map(value -> decode(value, QuarterOutcome.class)).toList();
    }

    static String quarter(String start, int offset) {
        int index = Integer.parseInt(start.substring(0, 4)) * 4 + Integer.parseInt(start.substring(4)) - 1 + offset;
        return "" + (index / 4) + (index % 4 + 1);
    }
    public void applyStats(long gameId, List<GameTypes.StatChange> changes) {
        for (var change : changes) {
            // SQL 식별자는 바인딩할 수 없으므로 고정 화이트리스트만 허용한다.
            if (!OwnerCandidates.STATS.contains(change.stat())) {
                throw new IllegalStateException("알 수 없는 능력치입니다.");
            }
            jdbc.update("UPDATE owners SET " + change.stat() + " = ? WHERE game_id = ?", change.after(), gameId);
        }
    }

    public void finishQuarter(long gameId, long cash, int negatives, String ending) {
        jdbc.update("""
                UPDATE games SET current_turn = current_turn + 1, version = version + 1,
                    cash = ?, negative_quarters = ?, status = ?, ending_type = ? WHERE id = ?
                """, cash, negatives, ending == null ? "PLAYING" : "ENDED", ending, gameId);
    }}

