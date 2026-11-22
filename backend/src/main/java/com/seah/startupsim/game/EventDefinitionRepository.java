package com.seah.startupsim.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 정의를 매번 최신 상태로 읽고 잘못된 DSL은 비활성 변경 없이 해당 사용에서만 제외한다. */
@Repository
public class EventDefinitionRepository {
    private static final Logger LOG = LoggerFactory.getLogger(EventDefinitionRepository.class);
    private static final String TURNING_POINTS = """
            SELECT p.id, p.rev, jsonb_build_object(
                'code', p.code, 'title', p.title, 'description', p.description,
                'tier', p.tier, 'source', p.source,
                'conditionExpr', p.condition_expr, 'baseProbability', p.base_probability,
                'statBonus', p.stat_bonus, 'probMin', p.prob_min, 'probMax', p.prob_max,
                'cooldownQuarters', p.cooldown_quarters, 'maxFires', p.max_fires,
                'minTurn', p.min_turn, 'priority', p.priority, 'enabled', p.enabled,
                'options', (SELECT jsonb_agg(jsonb_build_object(
                    'orderNo', o.order_no, 'label', o.label, 'successFormula', o.success_formula,
                    'onSuccess', o.on_success, 'onFail', o.on_fail,
                    'successText', o.success_text, 'failText', o.fail_text) ORDER BY o.order_no)
                    FROM turning_point_options o WHERE o.turning_point_id = p.id))::text AS definition
            FROM turning_points p WHERE (NOT ? OR p.enabled) ORDER BY p.priority DESC, p.code
            """;
    private static final String REACTIONS = """
            SELECT id, rev, jsonb_build_object('code', code, 'kind', kind, 'author', author,
                'rating', rating, 'weight', weight, 'enabled', enabled,
                'conditionExpr', condition_expr, 'text', body)::text AS definition
            FROM reaction_templates WHERE (NOT ? OR enabled) ORDER BY code
            """;
    private static final String MOMENTS = """
            SELECT id, rev, jsonb_build_object('code', code, 'title', title, 'stat', stat,
                'difficulty', difficulty, 'weight', weight, 'enabled', enabled,
                'conditionExpr', condition_expr, 'successText', success_text,
                'failText', fail_text, 'onSuccess', on_success, 'onFail', on_fail)::text AS definition
            FROM moment_templates WHERE (NOT ? OR enabled) ORDER BY code
            """;

    public record Definition(long id, int rev, JsonNode data) {
        public String code() {
            return data.get("code").asString();
        }
    }

    /** 관리자 화면이 다루는 정의 종류. 테이블 이름은 고정 목록에서만 고른다. */
    public enum Kind {
        TURNING_POINT("turning_points", TURNING_POINTS),
        REACTION("reaction_templates", REACTIONS),
        MOMENT("moment_templates", MOMENTS);

        private final String table;
        private final String select;

        Kind(String table, String select) {
            this.table = table;
            this.select = select;
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final EventDefinitionValidator validator;

    public EventDefinitionRepository(JdbcTemplate jdbc, ObjectMapper json, EventDefinitionValidator validator) {
        this.jdbc = jdbc;
        this.json = json;
        this.validator = validator;
    }

    public List<Definition> turningPoints() {
        return load(TURNING_POINTS, true, "turning_points", validator::turningPoint);
    }

    public List<Definition> reactions() {
        return load(REACTIONS, true, "reaction_templates", validator::reaction);
    }

    public List<Definition> moments() {
        return load(MOMENTS, true, "moment_templates", validator::moment);
    }

    /** 부팅 후 비활성 정의까지 전수 검사하고 DB의 활성 플래그는 변경하지 않는다. */
    @EventListener(ApplicationReadyEvent.class)
    public void validateAll() {
        load(TURNING_POINTS, false, "turning_points", validator::turningPoint);
        load(REACTIONS, false, "reaction_templates", validator::reaction);
        load(MOMENTS, false, "moment_templates", validator::moment);
    }

    /** 순서 번호와 선택지 ID의 대응은 발생 시 스냅샷에만 사용한다. */
    public Map<Integer, Long> optionIds(long turningPointId) {
        Map<Integer, Long> ids = new LinkedHashMap<>();
        jdbc.query("""
                SELECT order_no, id FROM turning_point_options WHERE turning_point_id = ? ORDER BY order_no
                """, row -> {
                    ids.put(row.getInt("order_no"), row.getLong("id"));
                }, turningPointId);
        return Map.copyOf(ids);
    }

    /** 관리자 목록용: 검증하지 않고 비활성·오류 정의까지 모두 읽는다. */
    public List<Definition> raw(Kind kind) {
        return jdbc.query(kind.select, (row, index) -> new Definition(row.getLong("id"), row.getInt("rev"),
                json.readTree(row.getString("definition"))), false);
    }

    public Definition raw(Kind kind, long id) {
        return raw(kind).stream().filter(definition -> definition.id() == id).findFirst()
                .orElseThrow(() -> new GameException(404, "NOT_FOUND", "정의를 찾을 수 없습니다."));
    }

    /** 최근 30일 전환점별 발생 수. */
    public Map<Long, Long> recentFires() {
        Map<Long, Long> counts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT turning_point_id, count(*) AS fires FROM game_turning_points
                WHERE created_at > now() - interval '30 days' GROUP BY turning_point_id
                """, row -> {
                    counts.put(row.getLong("turning_point_id"), row.getLong("fires"));
                });
        return counts;
    }

    /** 활성 여부를 뒤집고 리비전을 올린다. 대기 중인 전환점 스냅샷은 그대로다. */
    public void toggle(Kind kind, long id) {
        int updated = jdbc.update("UPDATE " + kind.table + " SET enabled = NOT enabled, rev = rev + 1, updated_at = now() WHERE id = ?", id);
        if (updated == 0) {
            throw new GameException(404, "NOT_FOUND", "정의를 찾을 수 없습니다.");
        }
    }

    /** 호출자가 검증을 끝낸 정의를 저장한다. code는 바꿀 수 없고 리비전은 1 오른다. */
    public void update(Kind kind, long id, JsonNode data) {
        Definition current = raw(kind, id);
        if (!current.code().equals(data.get("code").asString())) {
            throw new GameException(400, "INVALID_EXPRESSION", "code: 코드는 바꿀 수 없습니다.");
        }
        boolean enabled = data.has("enabled") ? data.get("enabled").booleanValue() : current.data().get("enabled").booleanValue();
        switch (kind) {
            case TURNING_POINT -> updateTurningPoint(id, data, enabled);
            case REACTION -> jdbc.update("""
                    UPDATE reaction_templates SET kind = ?, author = ?, rating = ?, weight = ?, condition_expr = ?::jsonb,
                        body = ?, enabled = ?, rev = rev + 1, updated_at = now() WHERE id = ?
                    """, data.get("kind").asString(), data.get("author").asString(),
                    data.has("rating") && !data.get("rating").isNull() ? data.get("rating").intValue() : null,
                    integer(data, "weight", 10), json.writeValueAsString(data.get("conditionExpr")),
                    data.get("text").asString(), enabled, id);
            case MOMENT -> jdbc.update("""
                    UPDATE moment_templates SET title = ?, stat = ?, difficulty = ?, weight = ?, condition_expr = ?::jsonb,
                        success_text = ?, fail_text = ?, on_success = ?::jsonb, on_fail = ?::jsonb, enabled = ?,
                        rev = rev + 1, updated_at = now() WHERE id = ?
                    """, data.get("title").asString(), data.get("stat").asString(), data.get("difficulty").intValue(),
                    integer(data, "weight", 10), json.writeValueAsString(data.get("conditionExpr")),
                    data.get("successText").asString(), data.get("failText").asString(),
                    json.writeValueAsString(data.get("onSuccess")), json.writeValueAsString(data.get("onFail")), enabled, id);
        }
    }

    private void updateTurningPoint(long id, JsonNode data, boolean enabled) {
        jdbc.update("""
                UPDATE turning_points SET title = ?, description = ?, condition_expr = ?::jsonb, base_probability = ?,
                    stat_bonus = ?::jsonb, prob_min = ?, prob_max = ?, cooldown_quarters = ?, max_fires = ?,
                    min_turn = ?, priority = ?, enabled = ?, tier = ?, source = ?, rev = rev + 1, updated_at = now() WHERE id = ?
                """, data.get("title").asString(), data.has("description") ? data.get("description").asString() : "",
                json.writeValueAsString(data.get("conditionExpr")), data.get("baseProbability").doubleValue(),
                data.has("statBonus") ? json.writeValueAsString(data.get("statBonus")) : "[]",
                data.has("probMin") ? data.get("probMin").doubleValue() : 0,
                data.has("probMax") ? data.get("probMax").doubleValue() : 1,
                integer(data, "cooldownQuarters", 4), integer(data, "maxFires", 1), integer(data, "minTurn", 0),
                integer(data, "priority", 50), enabled,
                data.has("tier") ? data.get("tier").asString() : raw(Kind.TURNING_POINT, id).data().get("tier").asString(),
                data.has("source") ? data.get("source").asString() : raw(Kind.TURNING_POINT, id).data().get("source").asString(), id);
        List<Integer> orders = new ArrayList<>();
        for (JsonNode option : data.get("options")) {
            orders.add(option.get("orderNo").intValue());
            jdbc.update("""
                    INSERT INTO turning_point_options
                        (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text)
                    VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                    ON CONFLICT (turning_point_id, order_no) DO UPDATE SET label = excluded.label,
                        success_formula = excluded.success_formula, on_success = excluded.on_success,
                        on_fail = excluded.on_fail, success_text = excluded.success_text, fail_text = excluded.fail_text
                    """, id, option.get("orderNo").intValue(), option.get("label").asString(),
                    json.writeValueAsString(option.get("successFormula")), json.writeValueAsString(option.get("onSuccess")),
                    json.writeValueAsString(option.get("onFail")), option.get("successText").asString(),
                    option.get("failText").asString());
        }
        for (int order = 1; order <= 3; order++) {
            if (!orders.contains(order)) {
                jdbc.update("DELETE FROM turning_point_options WHERE turning_point_id = ? AND order_no = ?", id, order);
            }
        }
    }

    private static int integer(JsonNode data, String key, int fallback) {
        return data.has(key) && !data.get(key).isNull() ? data.get(key).intValue() : fallback;
    }

    private List<Definition> load(String sql, boolean enabledOnly, String table, Consumer<JsonNode> validation) {
        List<Definition> definitions = new ArrayList<>();
        jdbc.query(sql, row -> {
            long id = row.getLong("id");
            try {
                JsonNode data = json.readTree(row.getString("definition"));
                validation.accept(data);
                definitions.add(new Definition(id, row.getInt("rev"), data));
            } catch (GameException exception) {
                LOG.error("이벤트 정의 검증 실패: table={}, id={}, error={}", table, id, exception.getMessage());
            }
        }, enabledOnly);
        return List.copyOf(definitions);
    }
}
