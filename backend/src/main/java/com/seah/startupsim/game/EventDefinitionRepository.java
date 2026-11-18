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
