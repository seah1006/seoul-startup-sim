package com.seah.startupsim.game;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 엔딩이 나는 모든 경로가 같은 트랜잭션에서 game_records를 정확히 1행 남기는지 검증한다. */
@Testcontainers
@SpringBootTest
class EndingIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired GuestAuth auth;
    @Autowired GameService games;
    @Autowired GameRepository repository;
    @Autowired AdvanceService advance;
    @Autowired EndingService sell;
    @Autowired WebApplicationContext context;
    @Autowired tools.jackson.databind.ObjectMapper json;
    long user;
    String token;

    @BeforeEach
    void fixture() {
        jdbc.execute("TRUNCATE turning_points, turning_point_options, reaction_templates, moment_templates CASCADE");
        jdbc.execute("TRUNCATE users, trade_areas, data_versions, stores CASCADE");
        var guest = auth.create();
        user = guest.userId();
        token = guest.guestToken();
        jdbc.update("INSERT INTO data_versions (id, source, status) VALUES (100, 'fixture', 'ACTIVE')");
        jdbc.update("""
                INSERT INTO trade_areas (area_code, name, district_code, geom, centroid, version_id, area_m2)
                VALUES ('A', '테스트 상권', '11440',
                    ST_Multi(ST_GeomFromText('POLYGON((126 37,128 37,128 39,126 39,126 37))',4326)),
                    ST_SetSRID(ST_MakePoint(127,38),4326), 100, 1000000)
                """);
        for (String quarter : List.of("20231", "20232", "20233", "20234", "20241", "20242")) {
            jdbc.update("""
                    INSERT INTO area_sales_q (quarter, area_code, service_code, sales_amount, version_id)
                    VALUES (?, 'A', 'CS100010', 1000000000, 100)
                    """, quarter);
            jdbc.update("""
                    INSERT INTO area_stores_q (quarter, area_code, service_code, store_count,
                                               opening_count, closing_count, version_id)
                    VALUES (?, 'A', 'CS100010', 10, 2, 1, 100)
                    """, quarter);
        }
    }

    long game() {
        long id = games.create(user, new GameTypes.Create("김사장", GameTypes.Background.OFFICE_WORKER,
                "c1", 123, List.of("service"), "20231")).gameId();
        games.shop(id, user, new GameTypes.Shop("A", "CS100010", 127, 38, "가상 가게"));
        return id;
    }

    GameResponses.Advanced next(long id, String key) {
        var state = repository.state(id, user);
        games.decision(id, user, state.currentTurn(), new GameTypes.Decision(GameTypes.Price.NORMAL,
                10, 2, 1000000, GameTypes.Activity.MENU_DEV));
        return advance.advance(id, user, key, state.version());
    }

    int records(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM game_records WHERE game_id = ?", Integer.class, id);
    }

    @Test
    @DisplayName("LONG_RUNNING 엔딩은 결산 이력과 엔딩 시점 스냅샷으로 기록 1행을 남기고 재시도해도 늘지 않는다")
    void longRunningRecord() {
        long id = game();
        jdbc.update("UPDATE games SET last_quarter = '20232' WHERE id = ?", id);
        next(id, "q0");
        assertEquals(0, records(id));
        var ended = next(id, "q1");
        assertEquals("LONG_RUNNING", ended.game().endingType());
        assertEquals(ended, advance.advance(id, user, "q1", ended.game().version() - 1));
        assertEquals(1, records(id));
        var row = jdbc.queryForMap("SELECT * FROM game_records WHERE game_id = ?", id);
        assertEquals(user, row.get("user_id"));
        assertEquals(jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, user), row.get("nickname"));
        assertEquals("LONG_RUNNING", row.get("ending_type"));
        assertEquals(2, row.get("survived_quarters"));
        assertEquals(jdbc.queryForObject("SELECT sum(profit) FROM turn_results WHERE game_id = ?", Long.class, id),
                row.get("total_profit"));
        assertEquals(jdbc.queryForObject("SELECT max(profit) FROM turn_results WHERE game_id = ?", Long.class, id),
                row.get("best_quarter_profit"));
        assertEquals(1, row.get("shop_count"));
        assertNull(row.get("sale_value"));
        assertEquals(List.of("A", "테스트 상권", "CS100010", "커피-음료", "가상 가게", "김사장"),
                List.of(row.get("area_code"), row.get("area_name"), row.get("service_code"), row.get("service_name"),
                        row.get("shop_name"), row.get("owner_name")));
        Instant endedAt = ((java.sql.Timestamp) row.get("ended_at")).toInstant();
        assertEquals(GameRecordRepository.rankingDate(endedAt), ((java.sql.Date) row.get("ranking_date")).toLocalDate());
    }

    @Test
    @DisplayName("CLOSED 엔딩은 2분기 연속 음수 잔액에서 기록 1행을 남긴다")
    void closedRecord() {
        long id = game();
        jdbc.update("UPDATE games SET cash = -100000000000 WHERE id = ?", id);
        next(id, "c0");
        var ended = next(id, "c1");
        assertEquals("CLOSED", ended.game().endingType());
        assertEquals(1, records(id));
        assertEquals("CLOSED", jdbc.queryForObject("SELECT ending_type FROM game_records WHERE game_id = ?", String.class, id));
    }

    void playQuarters(long id, int count) {
        for (int turn = 0; turn < count; turn++) {
            next(id, "p" + turn);
        }
    }

    long expectedSaleValue(long id) {
        long sum = jdbc.queryForObject("""
                SELECT sum(profit) FROM (SELECT profit FROM turn_results WHERE game_id = ? ORDER BY turn DESC LIMIT 4) recent
                """, Long.class, id);
        return Math.max(0, Math.round(sum / 4.0 * 4));
    }

    @Test
    @DisplayName("매각은 최근 4분기 평균×설정 분기 수를 SALE 원장과 잔액에 더하고 같은 키 재요청은 1회만 정산한다")
    void soldRecordAndIdempotency() {
        long id = game();
        playQuarters(id, 4);
        var before = repository.state(id, user);
        long value = expectedSaleValue(id);
        assertTrue(value > 0, "픽스처는 흑자여야 매각 가치 검증이 의미 있다");
        var sold = sell.sell(id, user, "sell-1", before.version());
        assertEquals("ENDED", sold.status());
        assertEquals("SOLD", sold.endingType());
        assertEquals(before.cash() + value, sold.cash());
        assertEquals(before.version() + 1, sold.version());
        assertEquals(sold, sell.sell(id, user, "sell-1", before.version()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE game_id = ? AND type = 'SALE'", Integer.class, id));
        assertEquals(value, jdbc.queryForObject("SELECT amount FROM ledger_entries WHERE game_id = ? AND type = 'SALE'", Long.class, id));
        assertEquals(sold.cash(), jdbc.queryForObject("""
                SELECT initial_capital + (SELECT sum(amount) FROM ledger_entries WHERE game_id = ?) FROM games WHERE id = ?
                """, Long.class, id, id));
        assertEquals(1, records(id));
        var row = jdbc.queryForMap("SELECT ending_type, sale_value, survived_quarters FROM game_records WHERE game_id = ?", id);
        assertEquals(List.of("SOLD", value, 4), List.of(row.get("ending_type"), row.get("sale_value"), row.get("survived_quarters")));
        assertEquals("GAME_ENDED", assertThrows(GameException.class, () -> sell.sell(id, user, "sell-2", sold.version())).code);
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(GameException.class,
                () -> advance.advance(id, user, "sell-1", sold.version())).code);
    }

    @Test
    @DisplayName("매각 거절: 4분기 미만 400, 버전 불일치 409, 전환점 대기 409, 평균 음수면 0원")
    void sellRejectionsAndZeroValue() {
        long id = game();
        playQuarters(id, 3);
        long version = repository.state(id, user).version();
        var early = assertThrows(GameException.class, () -> sell.sell(id, user, "early", version));
        assertEquals(400, early.status);
        assertEquals("VALIDATION_FAILED", early.code);
        next(id, "p3");
        long ready = repository.state(id, user).version();
        assertEquals("GAME_VERSION_CONFLICT", assertThrows(GameException.class, () -> sell.sell(id, user, "stale", ready - 1)).code);
        jdbc.update("INSERT INTO turning_points (code, title, base_probability, min_turn) VALUES ('SELL_TEST', '테스트', 1, 0)");
        jdbc.update("""
                INSERT INTO game_turning_points (game_id, turn, turning_point_id, definition_rev, payload)
                SELECT ?, 3, id, 1, '{}' FROM turning_points WHERE code = 'SELL_TEST'
                """, id);
        assertEquals("TURNING_POINT_PENDING", assertThrows(GameException.class, () -> sell.sell(id, user, "pending", ready)).code);
        jdbc.update("DELETE FROM game_turning_points WHERE game_id = ?", id);
        jdbc.update("UPDATE turn_results SET profit = -5000000 WHERE game_id = ?", id);
        long cash = repository.state(id, user).cash();
        var sold = sell.sell(id, user, "zero", ready);
        assertEquals(cash, sold.cash());
        assertEquals(0L, jdbc.queryForObject("SELECT sale_value FROM game_records WHERE game_id = ?", Long.class, id));
        assertEquals(0L, jdbc.queryForObject("SELECT amount FROM ledger_entries WHERE game_id = ? AND type = 'SALE'", Long.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE game_id = ? AND key IN ('early','stale','pending')", Integer.class, id));
    }

    @Test
    @DisplayName("매각 가치는 원 단위 반올림이며 음수·0 평균은 0원")
    void saleValueRounding() {
        assertEquals(41, EndingService.saleValue(List.of(10L, 11L, 10L, 10L, 999L), 4));
        assertEquals(2, EndingService.saleValue(List.of(3L, 0L, 0L, 0L), 2));
        assertEquals(2, EndingService.saleValue(List.of(1L, 2L, 0L, 0L), 3));
        assertEquals(0, EndingService.saleValue(List.of(-10L, 5L, 0L, 0L), 4));
        assertEquals(0, EndingService.saleValue(List.of(0L, 0L, 0L, 0L), 4));
    }

    @Test
    @DisplayName("같은 버전의 동시 매각은 하나만 성공하고 기록도 1행이다")
    void concurrentSell() throws Exception {
        long id = game();
        playQuarters(id, 4);
        long version = repository.state(id, user).version();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> call = () -> {
                gate.await();
                try {
                    sell.sell(id, user, UUID.randomUUID().toString(), version);
                    return 200;
                } catch (GameException exception) {
                    return exception.status;
                }
            };
            var first = pool.submit(call);
            var second = pool.submit(call);
            gate.countDown();
            assertEquals(List.of(200, 409), Stream.of(first.get(), second.get()).sorted().toList());
        }
        assertEquals(1, records(id));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE game_id = ? AND type = 'SALE'", Integer.class, id));
    }

    @Test
    @DisplayName("매각 HTTP: 게스트 토큰 허용, 키 누락 400, 남의 게임 403, 성공 200 GameState")
    void sellHttpContract() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        long id = game();
        playQuarters(id, 4);
        long version = repository.state(id, user).version();
        String path = "/api/games/" + id + "/sell";
        String body = "{\"expectedVersion\":" + version + "}";
        mvc.perform(post(path).header("X-Guest-Token", token).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(path).header("Idempotency-Key", "h").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        String other = auth.create().guestToken();
        mvc.perform(post(path).header("X-Guest-Token", other).header("Idempotency-Key", "h")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization", "Bearer " + token).header("Idempotency-Key", "h")
                .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endingType").value("SOLD")).andExpect(jsonPath("$.version").value(version + 1))
                .andExpect(jsonPath("$.pendingTurningPoint").value(false));
    }
    @Test
    @DisplayName("요약: 소유자만, 종료 전 409 GAME_NOT_ENDED, 종료 후 game_records 원본과 계약 필드")
    void summaryContract() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        long id = game();
        String path = "/api/games/" + id + "/summary";
        mvc.perform(get(path).header("X-Guest-Token", token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GAME_NOT_ENDED"));
        playQuarters(id, 4);
        sell.sell(id, user, "summary-sell", repository.state(id, user).version());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Guest-Token", auth.create().guestToken())).andExpect(status().isForbidden());
        mvc.perform(get("/api/games/999999/summary").header("X-Guest-Token", token)).andExpect(status().isNotFound());
        // 요약은 게임·가게 현재 값이 아니라 엔딩 기록을 읽는다.
        jdbc.update("UPDATE shops SET name = '엔딩 뒤 변경' WHERE game_id = ?", id);
        var row = jdbc.queryForMap("SELECT * FROM game_records WHERE game_id = ?", id);
        String body = mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(id))
                .andExpect(jsonPath("$.endingType").value("SOLD"))
                .andExpect(jsonPath("$.ownerName").value("김사장"))
                .andExpect(jsonPath("$.shopName").value("가상 가게"))
                .andExpect(jsonPath("$.areaName").value("테스트 상권"))
                .andExpect(jsonPath("$.serviceName").value(row.get("service_name")))
                .andExpect(jsonPath("$.survivedQuarters").value(4))
                .andExpect(jsonPath("$.totalProfit").value(row.get("total_profit")))
                .andExpect(jsonPath("$.bestQuarterProfit").value(row.get("best_quarter_profit")))
                .andExpect(jsonPath("$.shopCount").value(1))
                .andExpect(jsonPath("$.shareUrl").value("/share/" + id))
                .andReturn().getResponse().getContentAsString();
        assertEquals(List.of("gameId", "endingType", "ownerName", "shopName", "areaName", "serviceName", "survivedQuarters",
                "totalProfit", "bestQuarterProfit", "shopCount", "shareUrl"),
                List.copyOf(json.readTree(body).propertyNames()));
    }
    @Test
    @DisplayName("엔딩 기록 실패는 결산·종료 상태까지 함께 롤백한다(같은 트랜잭션)")
    void recordFailureRollsBackEnding() {
        long id = game();
        jdbc.update("UPDATE games SET last_quarter = '20231' WHERE id = ?", id);
        jdbc.update("""
                INSERT INTO game_records (game_id, user_id, nickname, ending_type, survived_quarters, total_profit,
                    best_quarter_profit, shop_count, area_code, area_name, service_code, service_name, shop_name,
                    owner_name, ranking_date)
                VALUES (?, ?, '선점', 'CLOSED', 0, 0, 0, 1, 'A', 'A', 'S', 'S', 'S', 'S', current_date)
                """, id, user);
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> next(id, "dup"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM turn_results WHERE game_id = ?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE game_id = ?", Integer.class, id));
        assertEquals("PLAYING", jdbc.queryForObject("SELECT status FROM games WHERE id = ?", String.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE game_id = ?", Integer.class, id));
    }
    @Test
    @DisplayName("랭킹 날짜는 KST 기준: 23:50은 그날, 00:10은 다음 날")
    void rankingDateUsesKst() {
        assertEquals(LocalDate.parse("2026-11-18"), GameRecordRepository.rankingDate(Instant.parse("2026-11-18T14:50:00Z")));
        assertEquals(LocalDate.parse("2026-11-19"), GameRecordRepository.rankingDate(Instant.parse("2026-11-18T15:10:00Z")));
    }
}
