package com.seah.startupsim.game;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** 결산 행 저장 후 같은 트랜잭션에서 판정한다. 0 기반 결산 turn을 achievedTurn에도 그대로 저장한다. */
public final class MissionRepository {
    private MissionRepository() {}
    public static Mission.State state(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT mission_code, mission_achieved_turn, shop_count FROM games WHERE id = ?", (row, index) -> {
            Mission mission = Mission.parse(row.getString("mission_code"));
            Integer achievedTurn = (Integer) row.getObject("mission_achieved_turn");
            Long value = switch (mission) {
                case SURVIVE_3Y -> jdbc.queryForObject("SELECT count(*) FROM turn_results WHERE game_id = ?", Long.class, id);
                case PROFIT_100M -> jdbc.queryForObject("SELECT coalesce(sum(profit),0) FROM turn_results WHERE game_id = ?", Long.class, id);
                case AREA_TOP3 -> jdbc.queryForObject("SELECT (SELECT area_rank FROM turn_results WHERE game_id = ? ORDER BY turn DESC LIMIT 1)", Long.class, id);
                case SECOND_SHOP -> row.getLong("shop_count");
                case BLACK_4 -> streak(jdbc.queryForList("SELECT profit FROM turn_results WHERE game_id = ? ORDER BY turn DESC", Long.class, id));
            };
            return new Mission.State(mission.name(), mission.title, mission.description,
                    new Mission.Progress(value, mission.target, mission.unit, mission.label(value)), achievedTurn != null, achievedTurn);
        }, id);
    }
    static long streak(List<Long> profits) {
        long count = 0;
        for (long profit : profits) { if (profit <= 0) break; count++; }
        return count;
    }
    public static Mission.Event evaluate(JdbcTemplate jdbc, long id, int turn) {
        var state = state(jdbc, id);
        if (state.achieved() || !Mission.parse(state.code()).achieved(state.progress().current())) return null;
        int changed = jdbc.update("UPDATE games SET mission_achieved_turn = ? WHERE id = ? AND mission_achieved_turn IS NULL", turn, id);
        return changed == 0 ? null : new Mission.Event(state.code(), state.title(), "목표 달성! " + state.title() + "에 성공했습니다.");
    }
    public static Mission.Ending ending(String code, boolean achieved, Integer turn) {
        var mission = Mission.parse(code);
        return new Mission.Ending(mission.name(), mission.title, achieved, turn);
    }
}
