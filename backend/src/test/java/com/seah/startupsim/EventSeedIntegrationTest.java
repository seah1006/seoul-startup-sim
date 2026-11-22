package com.seah.startupsim;

import com.seah.startupsim.game.EventDefinitionValidator;
import com.seah.startupsim.game.EventDefinitionRepository;
import com.seah.startupsim.game.GameException;
import com.seah.startupsim.game.TurnRepository;
import com.seah.startupsim.game.GameModifierRepository;
import com.seah.startupsim.game.QuarterCalculator.Modifier;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.DriverManager;
import java.sql.Connection;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 실제 PostgreSQL에서 V8 스키마와 생성된 콘텐츠의 제약·수를 확인한다. */
@Testcontainers
class EventSeedIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

    @Test
    void migrationLoadsAllDefinitionsAndOptions() throws Exception {
        Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT (SELECT count(*) FROM turning_points WHERE tier = 'MAJOR'),
                               (SELECT count(*) FROM turning_point_options o JOIN turning_points p ON p.id = o.turning_point_id WHERE p.tier = 'MAJOR'),
                               (SELECT count(*) FROM reaction_templates),
                               (SELECT count(*) FROM moment_templates),
                               (SELECT count(*) FROM turning_points p
                                WHERE (SELECT count(*) FROM turning_point_options o
                                       WHERE o.turning_point_id = p.id) NOT BETWEEN 2 AND 3),
                               (SELECT count(*) FROM turning_points WHERE jsonb_typeof(stat_bonus) <> 'array')
                        """)) {
            result.next();
            assertEquals(12, result.getInt(1));
            assertEquals(28, result.getInt(2));
            assertEquals(36, result.getInt(3));
            assertEquals(12, result.getInt(4));
            assertEquals(0, result.getInt(5));
            assertEquals(0, result.getInt(6));
            validateTuning(connection);
            validateDefinitions(connection);
            validateDefinitionReader(connection);
            validateModifierWindow(connection);
        }
    }

    /** V12: 빈도 조정 값과 효과 라벨 금액 단위(만 원)가 적용됐는지 본다. */
    private void validateTuning(Connection connection) {
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        assertEquals(0.08, jdbc.queryForObject(
                "SELECT base_probability FROM turning_points WHERE code = 'STAFF_TURNOVER'", Double.class));
        assertEquals(List.of(8, 2), jdbc.queryForObject(
                "SELECT ARRAY[cooldown_quarters, max_fires] FROM turning_points WHERE code = 'STAFF_TURNOVER'",
                (row, index) -> List.of((Integer[]) row.getArray(1).getArray())));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM turning_point_options
                WHERE on_success::text ~ '"label": "[^"]*[0-9]만"' OR on_fail::text ~ '"label": "[^"]*[0-9]만"'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM turning_point_options o JOIN turning_points p ON p.id = o.turning_point_id
                WHERE p.code = 'RENT_HIKE' AND o.on_success::text LIKE '%"임대료 +150만 원"%'
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM reaction_templates WHERE body ~ '\\{shopName\\}(이|은)'
                """, Integer.class));
        assertEquals(11, jdbc.queryForObject("""
                SELECT sum((SELECT count(*) FROM regexp_matches(body, '\\{shopName:[^}]+\\}', 'g')))
                FROM reaction_templates
                """, Integer.class));
    }

    private void validateDefinitionReader(Connection connection) {
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        var repository = new EventDefinitionRepository(jdbc, new ObjectMapper(), new EventDefinitionValidator());
        assertEquals(12, repository.turningPoints().stream().filter(p -> p.data().get("tier").asString().equals("MAJOR")).count());
        assertEquals(36, repository.reactions().size());
        assertEquals(12, repository.moments().size());
        var point = repository.turningPoints().stream().filter(p -> p.data().get("tier").asString().equals("MAJOR")).findFirst().orElseThrow();
        assertEquals(point.data().get("options").size(), repository.optionIds(point.id()).size());
        jdbc.update("UPDATE reaction_templates SET enabled = false WHERE code = (SELECT min(code) FROM reaction_templates)");
        assertEquals(35, repository.reactions().size());
        jdbc.update("UPDATE reaction_templates SET enabled = true");
        jdbc.update("""
                UPDATE turning_points SET condition_expr = '{"unknown":1}'
                WHERE code = (SELECT min(code) FROM turning_points WHERE tier = 'MAJOR')
                """);
        jdbc.update("""
                UPDATE reaction_templates SET body = '허용되지 않은 {unknown} 치환'
                WHERE code = (SELECT min(code) FROM reaction_templates)
                """);
        jdbc.update("""
                UPDATE moment_templates SET on_success = '[{"type":"CASH","amount":1,"memo":"금지"}]'
                WHERE code = (SELECT min(code) FROM moment_templates)
                """);
        repository.validateAll();
        assertEquals(11, repository.turningPoints().stream().filter(p -> p.data().get("tier").asString().equals("MAJOR")).count());
        assertEquals(35, repository.reactions().size());
        assertEquals(11, repository.moments().size());
        assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM turning_points WHERE enabled AND tier = 'MAJOR'", Integer.class));
        assertEquals(36, jdbc.queryForObject("SELECT count(*) FROM reaction_templates WHERE enabled", Integer.class));
        assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM moment_templates WHERE enabled", Integer.class));
    }

    private void validateModifierWindow(Connection connection) {
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.update("INSERT INTO users (id, nickname) VALUES (1, '보정 테스트')");
        jdbc.update("""
                INSERT INTO games (id, user_id, seed, start_quarter, last_quarter, cash, initial_capital, settings)
                VALUES (1, 1, 123, '20231', '20244', 50000000, 50000000, '{}'),
                       (2, 1, 123, '20231', '20244', 50000000, 50000000, '{}')
                """);
        jdbc.update("""
                INSERT INTO game_modifiers (game_id, source, target, mode, value, label, start_turn, end_turn)
                VALUES (1, 'TURNING_POINT', 'revenue', 'MULTIPLY', 1.2, '매출', 1, 3),
                       (1, 'MOMENT', 'rent', 'ADD', -1000, '임대료', 1, 2),
                       (1, 'TURNING_POINT', 'revenue', 'ADD', 1000, '추가 매출', 1, 3),
                       (2, 'TURNING_POINT', 'revenue', 'ADD', 9999, '다른 게임', 1, 3)
                """);
        var repository = new GameModifierRepository(jdbc);
        assertEquals(List.of(), repository.active(1, 0));
        assertEquals(List.of(new Modifier("rent", "ADD", -1000), new Modifier("revenue", "ADD", 1000),
                new Modifier("revenue", "MULTIPLY", 1.2)), repository.active(1, 1));
        assertEquals(List.of(new Modifier("revenue", "ADD", 1000), new Modifier("revenue", "MULTIPLY", 1.2)),
                repository.active(1, 2));
        assertEquals(List.of(), repository.active(1, 3));
        var turns = new TurnRepository(jdbc, new ObjectMapper());
        assertNull(turns.cached(1, "missing"));
        var choice = new CachedChoice(true);
        assertEquals(choice, turns.cache(1, "choice", "turning-point-choice", choice, CachedChoice.class));
        assertEquals(choice, turns.cached(1, "choice", "turning-point-choice", CachedChoice.class));
        assertThrows(GameException.class, () -> turns.cached(1, "choice"));
        assertThrows(GameException.class, () -> turns.cached(1, "choice", "sell", CachedChoice.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE game_id = 1 AND key = 'choice'", Integer.class));
    }

    public record CachedChoice(boolean success) {}

    private void validateDefinitions(Connection connection) throws Exception {
        var validator = new EventDefinitionValidator();
        validate(connection, """
                SELECT jsonb_build_object(
                    'code', p.code, 'title', p.title, 'description', p.description,
                    'conditionExpr', p.condition_expr, 'baseProbability', p.base_probability,
                    'statBonus', p.stat_bonus, 'probMin', p.prob_min, 'probMax', p.prob_max,
                    'cooldownQuarters', p.cooldown_quarters, 'maxFires', p.max_fires,
                    'minTurn', p.min_turn, 'priority', p.priority, 'enabled', p.enabled,
                    'options', (SELECT jsonb_agg(jsonb_build_object(
                        'orderNo', o.order_no, 'label', o.label, 'successFormula', o.success_formula,
                        'onSuccess', o.on_success, 'onFail', o.on_fail,
                        'successText', o.success_text, 'failText', o.fail_text) ORDER BY o.order_no)
                        FROM turning_point_options o WHERE o.turning_point_id = p.id))::text
                FROM turning_points p WHERE p.tier = 'MAJOR' ORDER BY p.code
                """, validator::turningPoint, 12);
        validate(connection, """
                SELECT jsonb_build_object('code', code, 'kind', kind, 'author', author,
                    'rating', rating, 'weight', weight, 'enabled', enabled,
                    'conditionExpr', condition_expr, 'text', body)::text
                FROM reaction_templates ORDER BY code
                """, validator::reaction, 36);
        validate(connection, """
                SELECT jsonb_build_object('code', code, 'title', title, 'stat', stat,
                    'difficulty', difficulty, 'weight', weight, 'enabled', enabled,
                    'conditionExpr', condition_expr, 'successText', success_text,
                    'failText', fail_text, 'onSuccess', on_success, 'onFail', on_fail)::text
                FROM moment_templates ORDER BY code
                """, validator::moment, 12);
    }

    private void validate(Connection connection, String sql, Consumer<JsonNode> validation, int expected) throws Exception {
        var json = new ObjectMapper();
        int count = 0;
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            while (result.next()) {
                validation.accept(json.readTree(result.getString(1)));
                count++;
            }
        }
        assertEquals(expected, count);
    }
}
