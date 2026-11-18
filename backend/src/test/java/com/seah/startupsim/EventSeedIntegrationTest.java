package com.seah.startupsim;

import com.seah.startupsim.game.EventDefinitionValidator;
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
                        SELECT (SELECT count(*) FROM turning_points),
                               (SELECT count(*) FROM turning_point_options),
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
            validateDefinitions(connection);
        }
    }

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
                FROM turning_points p ORDER BY p.code
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
