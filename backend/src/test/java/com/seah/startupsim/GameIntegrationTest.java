package com.seah.startupsim;

import com.seah.startupsim.game.AdvanceService;
import com.seah.startupsim.game.GameEndedEvent;
import com.seah.startupsim.game.GameException;
import com.seah.startupsim.game.GameRepository;
import com.seah.startupsim.game.GameService;
import com.seah.startupsim.game.GameTypes;
import com.seah.startupsim.game.GuestAuth;
import com.seah.startupsim.game.TurnEventHook;
import com.seah.startupsim.game.TurnResultBuilder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.stream.Stream;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/** 고정 시드·공유 픽스처로 계산 재현성과 트랜잭션 경계를 함께 검증한다. */
@Testcontainers
@SpringBootTest
@RecordApplicationEvents
class GameIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    GuestAuth auth;
    @Autowired
    GameService service;
    @Autowired
    AdvanceService advance;
    @Autowired
    GameRepository repository;
    @Autowired
    ObjectMapper json;
    @Autowired
    ApplicationEvents applicationEvents;
    @MockitoBean
    TurnEventHook eventHook;
    long user;

    @BeforeEach
    void fixture() {
        jdbc.execute("""
                TRUNCATE users, trade_areas, data_versions CASCADE
                """);
        user = auth.create().userId();
        jdbc.update("""
                INSERT INTO data_versions (id, source, status) VALUES (100, 'fixture', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO trade_areas (area_code, name, district_code, geom, centroid, version_id, area_m2)
                VALUES ('A', '테스트 상권', '11440',
                    ST_Multi(ST_GeomFromText('POLYGON((126 37,128 37,128 39,126 39,126 37))',4326)),
                    ST_SetSRID(ST_MakePoint(127,38),4326), 100, 1000000),
                    ('B', '이웃 상권', '11440',
                    ST_Multi(ST_GeomFromText('POLYGON((128 37,129 37,129 39,128 39,128 37))',4326)),
                    ST_SetSRID(ST_MakePoint(128.5,38),4326), 100, 1000000)
                """);
        for (String quarter : List.of("20231", "20232", "20233", "20234", "20241")) {
            jdbc.update("""
                    INSERT INTO area_sales_q (quarter, area_code, service_code, sales_amount, version_id)
                    VALUES (?, 'A', 'CS100010', 1000000000, 100), (?, 'B', 'CS100010', 500000000, 100)
                    """, quarter, quarter);
            jdbc.update("""
                    INSERT INTO area_stores_q (quarter, area_code, service_code, store_count,
                                               opening_count, closing_count, version_id)
                    VALUES (?, 'A', 'CS100010', 10, 2, 1, 100), (?, 'B', 'CS100010', 5, 1, 0, 100)
                    """, quarter, quarter);
        }
        jdbc.update("""
                INSERT INTO stores (store_id, name, small_code, geom, snapshot, version_id)
                VALUES ('S', '실제 사실표시용', 'I21201', ST_SetSRID(ST_MakePoint(127,38),4326), '20231', 100)
                """);
    }

    long game() {
        long id = service.create(user, new GameTypes.Create("테스트", GameTypes.Background.OFFICE_WORKER,
                "c1", 123, List.of("service"), "20231")).gameId();
        service.shop(id, user, new GameTypes.Shop("A", "CS100010", 127, 38, "가상 가게"));
        return id;
    }

    void decision(long id, int turn) {
        service.decision(id, user, turn, new GameTypes.Decision(GameTypes.Price.NORMAL,
                10, 2, 1000000, GameTypes.Activity.MENU_DEV));
    }

    @Test
    @DisplayName("같은 시드의 두 게임은 4분기 결과가 같고 원장과 현금이 일치한다")
    void reproducible() {
        long firstGame = game();
        long secondGame = game();
        for (int turn = 0; turn < 4; turn++) {
            decision(firstGame, turn);
            decision(secondGame, turn);
            var first = advance.advance(firstGame, user, UUID.randomUUID().toString(), turn + 1);
            var second = advance.advance(secondGame, user, UUID.randomUUID().toString(), turn + 1);
            assertEquals(first.result(), second.result());
        }
        assertEquals(jdbc.queryForObject("""
                SELECT cash FROM games WHERE id = ?
                """, Long.class, firstGame),
                jdbc.queryForObject("""
                        SELECT initial_capital + (SELECT sum(amount) FROM ledger_entries WHERE game_id = ?)
                        FROM games WHERE id = ?
                        """, Long.class, firstGame, firstGame));
    }

    @Test
    @DisplayName("고정 시드 4분기 JSON 회귀 스냅샷")
    void snapshot() throws Exception {
        long id = game();
        var results = new ArrayList<Object>();
        for (int turn = 0; turn < 4; turn++) {
            decision(id, turn);
            results.add(advance.advance(id, user, "snapshot-" + turn, turn + 1).result());
        }
        byte[] actual = json.writeValueAsString(results).getBytes(StandardCharsets.UTF_8);
        try (var golden = getClass().getResourceAsStream("/game/four-quarters.json")) {
            assertNotNull(golden);
            assertArrayEquals(golden.readAllBytes(), actual);
        }
    }

    @Test
    @DisplayName("확장 훅이 채운 표현은 응답과 결산 이력에 함께 저장된다")
    void hookPresentation() {
        doAnswer(invocation -> {
            TurnResultBuilder builder = invocation.getArgument(2);
            builder.moment("가상 순간");
            builder.reactions(List.of("가상 반응"));
            builder.pendingTurningPoint(true);
            return null;
        }).when(eventHook).afterQuarter(any(),
                any(), any());
        long id = game();
        decision(id, 0);
        var response = advance.advance(id, user, "hook", 1);
        var result = response.result();
        assertEquals("가상 순간", result.moment());
        assertEquals(List.of("가상 반응"), result.reactions());
        assertEquals(true, response.game().pendingTurningPoint());
        assertEquals(result, repository.turns(id, user).getFirst());
        assertEquals("[\"가상 반응\"]", jdbc.queryForObject(
                """
                        SELECT reactions::text FROM turn_results WHERE game_id=?
                        """, String.class, id));
    }

    @Test
    @DisplayName("확장 훅 실패는 결산·원장·게임 버전을 모두 롤백한다")
    void hookFailureRollsBack() {
        doThrow(new IllegalStateException("hook failure"))
                .when(eventHook).afterQuarter(any(),
                        any(), any());
        long id = game();
        decision(id, 0);
        assertThrows(IllegalStateException.class, () -> advance.advance(id, user, "failure", 1));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM turn_results WHERE game_id=?
                """, Integer.class, id));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM ledger_entries WHERE game_id=?
                """, Integer.class, id));
        assertEquals(1L, jdbc.queryForObject("""
                SELECT version FROM games WHERE id=?
                """, Long.class, id));
        assertEquals(0L, applicationEvents.stream(GameEndedEvent.class).count());
    }

    @Test
    @DisplayName("마지막 분기에 종료 이벤트는 한 번만 발행하고 기본 전환점은 없다")
    void endingEvent() {
        long id = game();
        for (int turn = 0; turn < 5; turn++) {
            decision(id, turn);
            advance.advance(id, user, "ending-" + turn, turn + 1);
        }
        advance.advance(id, user, "ending-4", 5);
        var events = applicationEvents.stream(GameEndedEvent.class).toList();
        assertEquals(1, events.size());
        assertEquals(id, events.getFirst().gameId());
        assertEquals("LONG_RUNNING", events.getFirst().endingType());
        assertEquals(false, repository.state(id, user).pendingTurningPoint());
    }

    @Test
    @DisplayName("멱등 키 재시도와 DB 유일 제약이 이중 결산을 막는다")
    void idempotency() {
        long id = game();
        decision(id, 0);
        var first = advance.advance(id, user, "once", 1);
        assertEquals(first, advance.advance(id, user, "once", 1));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM turn_results WHERE game_id=?
                """, Integer.class, id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO turn_results (game_id, turn, market_size, share, revenue, visitors, cost, profit,
                                          area_rank, stat_changes, reactions, response)
                SELECT game_id, turn, market_size, share, revenue, visitors, cost, profit,
                       area_rank, stat_changes, reactions, response FROM turn_results WHERE game_id = ?
                """, id));
    }

    @Test
    @DisplayName("같은 버전의 동시 진행은 하나만 성공한다")
    void concurrency() throws Exception {
        long id = game();
        decision(id, 0);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> call = () -> {
                gate.await();
                try {
                    advance.advance(id, user, UUID.randomUUID().toString(), 1);
                    return 200;
                } catch (GameException exception) {
                    return 409;
                }
            };
            var first = pool.submit(call);
            var second = pool.submit(call);
            gate.countDown();
            assertEquals(List.of(200, 409), Stream.of(first.get(), second.get()).sorted().toList());
        }
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM turn_results WHERE game_id=?
                """, Integer.class, id));
    }

    @Test
    @DisplayName("다른 게스트의 조회와 잘못된 토큰은 거절한다")
    void ownership() {
        long id = game();
        long other = auth.create().userId();
        assertThrows(GameException.class, () -> repository.state(id, other));
        assertThrows(GameException.class, () -> auth.resolve("invalid"));
        assertThrows(GameException.class, () -> auth.resolve(null));
    }

    @Test
    @DisplayName("점포 자료 누락 시 이전 점포 수와 최소 기본값을 사용하고 닫힌 경쟁점은 제외한다")
    void missingStoresAndClosedRivals() {
        long id = game();
        decision(id, 0);
        advance.advance(id, user, "before-missing", 1);
        jdbc.update("""
                DELETE FROM area_stores_q WHERE area_code='A' AND quarter='20232'
                """);
        jdbc.update("""
                DELETE FROM area_sales_q WHERE area_code='A' AND quarter='20232'
                """);
        jdbc.update("""
                UPDATE rivals SET closed_turn=1 WHERE game_id=?
                """, id);
        decision(id, 1);
        var result = advance.advance(id, user, "missing", 2).result();
        assertEquals(11, result.areaRank().of());
        assertEquals(1000000000L, jdbc.queryForObject(
                """
                        SELECT market_size FROM turn_results WHERE game_id=? AND turn=1
                        """, Long.class, id));
        assertEquals(0L, repository.state(id, user).shop().rivalCount());
        assertTrue(result.dataNotes().stream().anyMatch(note -> note.contains("직전")));
        long fallback = game();
        jdbc.update("""
                DELETE FROM area_stores_q WHERE area_code='A'
                """);
        jdbc.update("""
                DELETE FROM area_sales_q WHERE area_code='A'
                """);
        decision(fallback, 0);
        var fallbackResult = advance.advance(fallback, user, "fallback", 1).result();
        assertEquals(2, fallbackResult.areaRank().of());
        assertTrue(fallbackResult.dataNotes().stream().anyMatch(note -> note.contains("기본 점포 수 1개")));
    }

    @Test
    @DisplayName("경쟁점 bbox 선필터는 반경 안 점포를 유지하고 먼 점포를 제외한다")
    void rivalBoundingBox() {
        jdbc.update("""
                INSERT INTO stores(store_id,name,small_code,geom,snapshot,version_id) VALUES
                    ('NEAR','인근 사실표시용','I21201',ST_SetSRID(ST_MakePoint(127.001,38),4326),'20231',100),
                    ('FAR','원거리 사실표시용','I21201',ST_SetSRID(ST_MakePoint(128,38),4326),'20231',100)
                """);
        long id = game();
        assertEquals(List.of("NEAR", "S"), jdbc.queryForList(
                """
                        SELECT sbiz_store_id FROM rivals WHERE game_id=? ORDER BY sbiz_store_id
                        """, String.class, id));
    }

    @Test
    @DisplayName("필수 설정의 null·문자열·누락은 명확한 오류로 거절한다")
    void invalidSettings() {
        String original = jdbc.queryForObject("""
                SELECT value::text FROM game_settings WHERE key='initialCapital'
                """, String.class);
        try {
            for (String invalid : List.of("null", "\"잘못된 숫자\"")) {
                jdbc.update("""
                        UPDATE game_settings SET value=?::jsonb WHERE key='initialCapital'
                        """, invalid);
                var exception = assertThrows(GameException.class, this::game);
                assertTrue(exception.getMessage().contains("initialCapital"));
            }
            jdbc.update("""
                    DELETE FROM game_settings WHERE key='initialCapital'
                    """);
            var exception = assertThrows(GameException.class, this::game);
            assertTrue(exception.getMessage().contains("필수 게임 설정"));
        } finally {
            jdbc.update("""
                    INSERT INTO game_settings(key,value) VALUES ('initialCapital',?::jsonb) ON CONFLICT(key) DO UPDATE SET value=excluded.value
                    """, original);
        }
    }
}
