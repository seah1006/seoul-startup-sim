package com.seah.startupsim.game;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 훅·행 잠금·스냅샷을 통해 전환점 발생부터 선택·재개까지 검증한다. */
@Testcontainers
@SpringBootTest
class TurningPointIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired GuestAuth auth;
    @Autowired GameService games;
    @Autowired GameRepository repository;
    @Autowired AdvanceService advance;
    @Autowired TurningPointService points;
    @Autowired GameModifierRepository modifiers;
    long user;
    long game;

    @BeforeEach
    void fixture() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
        jdbc.execute("TRUNCATE users, trade_areas, data_versions, turning_points, reaction_templates, moment_templates CASCADE");
        user = auth.create().userId();
        jdbc.update("UPDATE game_settings SET value = '0' WHERE key = 'event.fire.minTurn'");
        jdbc.update("INSERT INTO data_versions (id, source, status) VALUES (100, 'fixture', 'ACTIVE')");
        jdbc.update("""
                INSERT INTO trade_areas (area_code, name, district_code, geom, centroid, version_id, area_m2)
                VALUES ('A', '테스트 상권', '11440',
                    ST_Multi(ST_GeomFromText('POLYGON((126 37,128 37,128 39,126 39,126 37))',4326)),
                    ST_SetSRID(ST_MakePoint(127,38),4326), 100, 1000000)
                """);
        for (String quarter : List.of("20231", "20232", "20233", "20234")) {
            jdbc.update("INSERT INTO area_sales_q (quarter, area_code, service_code, sales_amount, version_id) VALUES (?, 'A', 'CS100010', 1000000000, 100)", quarter);
            jdbc.update("INSERT INTO area_stores_q (quarter, area_code, service_code, store_count, opening_count, closing_count, version_id) VALUES (?, 'A', 'CS100010', 10, 2, 1, 100)", quarter);
        }
        game = games.create(user, new GameTypes.Create("사장", GameTypes.Background.OFFICE_WORKER, "c1", 123, List.of("service"), "20231")).gameId();
        games.shop(game, user, new GameTypes.Shop("A", "CS100010", 127, 38, "가상 가게"));
        jdbc.update("INSERT INTO turning_points (code, title, base_probability, min_turn) VALUES ('TEST_POINT', '테스트 전환점', 1, 0)");
        jdbc.update("""
                INSERT INTO turning_point_options
                    (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text)
                SELECT id, 1, '도전', '{"base":1,"min":1,"max":1}',
                    '[{"type":"CASH","amount":1000,"memo":"지원금"},
                      {"type":"STAT","stat":"network","delta":2},
                      {"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":1.2,"quarters":1,"label":"매출 지원"},
                      {"type":"FLAG","flag":"TEST_FLAG","value":true},
                      {"type":"SHOP_COUNT","delta":1},
                      {"type":"RIVAL_ADD","count":1,"franchise":false},
                      {"type":"TEXT","text":"안내"}]', '[]', '성공', '실패'
                FROM turning_points WHERE code = 'TEST_POINT'
                """);
        jdbc.update("""
                INSERT INTO turning_point_options
                    (turning_point_id, order_no, label, success_formula, on_success, on_fail, success_text, fail_text)
                SELECT id, 2, '다른 선택', '{"base":0,"min":0,"max":0}', '[]',
                    '[{"type":"CASH","amount":-50,"memo":"실패 비용"}]', '성공', '실패'
                FROM turning_points WHERE code = 'TEST_POINT'
                """);
        });
    }

    private GameResponses.Advanced next(String key) {
        var state = repository.state(game, user);
        games.decision(game, user, state.currentTurn(), new GameTypes.Decision(GameTypes.Price.NORMAL, 10, 2, 1000000, GameTypes.Activity.MENU_DEV));
        return advance.advance(game, user, key, state.version());
    }

    @Test
    void pendingSnapshotChoiceIdempotencyAndResume() {
        var settled = next("advance");
        assertTrue(settled.game().pendingTurningPoint());
        assertEquals("TURNING_POINT_PENDING", assertThrows(GameException.class,
                () -> advance.advance(game, user, "blocked", settled.game().version())).code);
        var view = points.view(game, user);
        assertNotNull(view);
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(GameException.class,
                () -> points.choose(game, user, "advance", view.options().getFirst().optionId())).code);
        jdbc.update("UPDATE turning_point_options SET on_success = '[]', success_text = '수정됨'");
        jdbc.update("UPDATE turning_points SET rev = rev + 1, enabled = false");
        var chosen = points.choose(game, user, "choice", view.options().getFirst().optionId());
        assertTrue(chosen.success());
        assertEquals("성공", chosen.resultText());
        assertEquals(settled.game().cash() + 1000, chosen.game().cash());
        assertEquals(settled.game().version() + 1, chosen.game().version());
        assertEquals(settled.game().currentTurn(), chosen.game().currentTurn());
        assertFalse(chosen.game().pendingTurningPoint());
        assertNull(points.view(game, user));
        assertEquals(chosen, points.choose(game, user, "choice", view.options().getFirst().optionId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE game_id = ? AND type = 'EVENT'", Integer.class, game));
        assertEquals(2, jdbc.queryForObject("SELECT shop_count FROM games WHERE id = ?", Integer.class, game));
        assertEquals(1, modifiers.active(game, 1).size());
        assertTrue(modifiers.active(game, 2).isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game_flags WHERE game_id = ?", Integer.class, game));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM rivals WHERE game_id = ? AND is_virtual", Integer.class, game));
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(GameException.class,
                () -> advance.advance(game, user, "choice", chosen.game().version())).code);
        assertEquals(2, next("resumed").game().currentTurn());
    }

    @Test
    void failedChoiceAndConcurrentChoicesApplyOnlyOnce() throws Exception {
        next("advance");
        long option = points.view(game, user).options().get(1).optionId();
        long cash = repository.state(game, user).cash();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> task = () -> {
                gate.await();
                try {
                    var chosen = points.choose(game, user, java.util.UUID.randomUUID().toString(), option);
                    assertFalse(chosen.success());
                    return 200;
                } catch (GameException exception) {
                    return exception.status;
                }
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            gate.countDown();
            assertEquals(List.of(200, 409), java.util.stream.Stream.of(first.get(), second.get()).sorted().toList());
        }
        assertEquals(cash - 50, repository.state(game, user).cash());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE type = 'EVENT' AND game_id = ?", Integer.class, game));
    }

    @Test
    void endingQuarterDoesNotCreateTurningPoint() {
        jdbc.update("UPDATE games SET last_quarter = '20231' WHERE id = ?", game);
        var result = next("ended");
        assertEquals("ENDED", result.game().status());
        assertFalse(result.game().pendingTurningPoint());
        assertNull(points.view(game, user));
    }
}
