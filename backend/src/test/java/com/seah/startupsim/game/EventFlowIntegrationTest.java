package com.seah.startupsim.game;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V8 시드 콘텐츠를 켠 상태에서 8분기 재현성, 반응·순간 형식, 정의 추가에 대한 판정 불변을 검증한다. */
@Testcontainers
@SpringBootTest
class EventFlowIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    private static final String GOLDEN = "game/events-on-eight-quarters.json";
    private static final List<String> QUARTERS = List.of("20231", "20232", "20233", "20234", "20241", "20242",
            "20243", "20244", "20251");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    /** 골든 한 분기: 게임 ID처럼 실행마다 달라지는 값은 넣지 않는다. */
    record Quarter(QuarterOutcome result, String turningPoint, List<Integer> successRates, String chosen,
            Boolean success, String resultText, List<EventEffectService.EffectView> effects, long cash,
            long version, int shopCount, Map<String, Integer> stats) {}

    @Autowired JdbcTemplate jdbc;
    @Autowired GuestAuth auth;
    @Autowired GameService games;
    @Autowired GameRepository repository;
    @Autowired AdvanceService advance;
    @Autowired TurningPointService points;
    @Autowired EventDefinitionRepository definitions;
    @Autowired EventDefinitionValidator validator;
    @Autowired ObjectMapper json;
    long user;

    @BeforeEach
    void fixture() {
        jdbc.execute("TRUNCATE users, trade_areas, data_versions, stores CASCADE");
        user = auth.create().userId();
        jdbc.update("INSERT INTO data_versions (id, source, status) VALUES (100, 'fixture', 'ACTIVE')");
        jdbc.update("""
                INSERT INTO trade_areas (area_code, name, district_code, geom, centroid, version_id, area_m2, area_type_name)
                VALUES ('A', '테스트 상권', '11440',
                    ST_Multi(ST_GeomFromText('POLYGON((126 37,128 37,128 39,126 39,126 37))',4326)),
                    ST_SetSRID(ST_MakePoint(127,38),4326), 100, 1000000, '골목상권')
                """);
        for (int index = 0; index < QUARTERS.size(); index++) {
            jdbc.update("""
                    INSERT INTO area_sales_q (quarter, area_code, service_code, sales_amount, version_id)
                    VALUES (?, 'A', 'CS100010', ?, 100)
                    """, QUARTERS.get(index), 900000000L + index * 60000000L);
            jdbc.update("""
                    INSERT INTO area_stores_q (quarter, area_code, service_code, store_count, franchise_count,
                                               opening_count, closing_count, opening_rate, closing_rate, version_id)
                    VALUES (?, 'A', 'CS100010', ?, 2, ?, ?, ?, ?, 100)
                    """, QUARTERS.get(index), 10 + index % 3, 1 + index % 3, index % 2, 10 + index, 5 + index * 2);
        }
        jdbc.update("""
                INSERT INTO stores (store_id, name, small_code, geom, snapshot, version_id)
                VALUES ('S', '실제사실표시상호', 'I21201', ST_SetSRID(ST_MakePoint(127,38),4326), '20231', 100)
                """);
    }

    private long game(long seed) {
        long id = games.create(user, new GameTypes.Create("사장", GameTypes.Background.OFFICE_WORKER, "c1", seed,
                List.of("service"), "20231")).gameId();
        games.shop(id, user, new GameTypes.Shop("A", "CS100010", 127, 38, "가상 가게"));
        return id;
    }

    /** 같은 시드·같은 선택 규칙(분기 번호로 선택지 순번 결정)으로 8분기를 진행한다. */
    private List<Quarter> play(long id, String prefix) {
        List<Quarter> quarters = new ArrayList<>();
        for (int turn = 0; turn < 8; turn++) {
            var state = repository.state(id, user);
            games.decision(id, user, turn, new GameTypes.Decision(turn % 3 == 0 ? GameTypes.Price.HIGH : GameTypes.Price.NORMAL,
                    9 + turn % 5, 2, 1000000L * (turn % 4), GameTypes.Activity.values()[turn % 4]));
            var settled = advance.advance(id, user, prefix + "-advance-" + turn, state.version());
            var view = points.view(id, user);
            if (view == null) {
                quarters.add(new Quarter(settled.result(), null, null, null, null, null, null,
                        settled.game().cash(), settled.game().version(), shopCount(id), settled.game().owner().stats()));
                continue;
            }
            var option = view.options().get(turn % view.options().size());
            var chosen = points.choose(id, user, prefix + "-choice-" + turn, option.optionId());
            quarters.add(new Quarter(settled.result(), view.code(),
                    view.options().stream().map(TurningPointService.OptionView::successRatePct).toList(), option.label(),
                    chosen.success(), chosen.resultText(), chosen.effects(), chosen.game().cash(),
                    chosen.game().version(), shopCount(id), chosen.game().owner().stats()));
        }
        return quarters;
    }

    private int shopCount(long id) {
        return jdbc.queryForObject("SELECT shop_count FROM games WHERE id = ?", Integer.class, id);
    }

    @Test
    @DisplayName("이벤트를 켠 고정 시드 8분기는 두 번 진행해도 바이트가 같고 골든 파일과 일치한다")
    void eightQuarterGolden() throws Exception {
        long first = game(20261118);
        long second = game(20261118);
        byte[] firstBytes = json.writeValueAsString(play(first, "a")).getBytes(StandardCharsets.UTF_8);
        List<Quarter> quarters = play(second, "b");
        byte[] secondBytes = json.writeValueAsString(quarters).getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(firstBytes, secondBytes);
        writeDemo(quarters);
        if ("1".equals(System.getenv("WRITE_EVENT_GOLDEN"))) {
            Files.write(Path.of("src/test/resources", GOLDEN), secondBytes);
        }
        try (var golden = getClass().getResourceAsStream("/" + GOLDEN)) {
            assertNotNull(golden);
            assertArrayEquals(golden.readAllBytes(), secondBytes);
        }
        assertTrue(quarters.stream().anyMatch(quarter -> quarter.turningPoint() != null));
        assertTrue(quarters.stream().anyMatch(quarter -> quarter.result().moment() != null));
        for (Quarter quarter : quarters) {
            List<Object> reactions = quarter.result().reactions();
            assertTrue(reactions.size() >= 2 && reactions.size() <= 3, "반응은 2~3개");
            String text = json.writeValueAsString(reactions);
            assertFalse(text.contains("{shopName}") || text.contains("{areaName}") || text.contains("{serviceName}"));
            assertFalse(text.contains("실제사실표시상호"));
            assertTrue(text.contains("\"kind\":\"REVIEW\""), "첫 반응은 리뷰 풀에서 뽑는다");
        }
        assertEquals(jdbc.queryForObject("SELECT cash FROM games WHERE id = ?", Long.class, second),
                jdbc.queryForObject("""
                        SELECT initial_capital + (SELECT sum(amount) FROM ledger_entries WHERE game_id = ?)
                        FROM games WHERE id = ?
                        """, Long.class, second, second));
        assertEquals(8, jdbc.queryForObject("""
                SELECT count(*) FROM turn_results WHERE game_id = ? AND reactions = response -> 'reactions'
                    AND moment IS NOT DISTINCT FROM CASE WHEN response -> 'moment' = 'null'::jsonb THEN NULL ELSE response -> 'moment' END
                """, Integer.class, second));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM ledger_entries WHERE game_id = ? AND type = 'EVENT'
                    AND turn NOT IN (SELECT turn FROM game_turning_points WHERE game_id = ?)
                """, Integer.class, second, second), "순간은 현금 원장을 만들지 않는다");
    }

    /** 데모 표는 빌드 산출물로만 남기고 PROGRESS에 옮겨 적는다. */
    private void writeDemo(List<Quarter> quarters) throws Exception {
        StringBuilder table = new StringBuilder("| 분기 | 순이익 | 전환점 | 선택 | 결과 | 반응 수 | 순간 |\n|---|---:|---|---|---|---:|---|\n");
        for (Quarter quarter : quarters) {
            Object moment = quarter.result().moment();
            table.append("| %s | %,d | %s | %s | %s | %d | %s |\n".formatted(quarter.result().quarter(),
                    quarter.result().profit(), quarter.turningPoint() == null ? "-" : quarter.turningPoint(),
                    quarter.chosen() == null ? "-" : quarter.chosen(),
                    quarter.success() == null ? "-" : quarter.success() ? "성공" : "실패",
                    quarter.result().reactions().size(),
                    moment == null ? "-" : ((Map<?, ?>) moment).get("title") + (Boolean.TRUE.equals(((Map<?, ?>) moment).get("success")) ? " (성공)" : " (실패)")));
        }
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(Path.of("build/reports/events-demo.md"), table.toString());
    }

    @Test
    @DisplayName("전환점 정의를 추가하거나 순서를 바꿔도 기존 정의의 발생 판정은 그대로다")
    void addingDefinitionKeepsOtherJudgements() {
        var seeded = definitions.turningPoints();
        assertEquals(12, seeded.size());
        var extra = new EventDefinitionRepository.Definition(999, 1, json.readTree("""
                {"code":"ZZ_NEW_EVENT","title":"새 전환점","description":"","conditionExpr":{},
                 "baseProbability":0.9,"statBonus":[],"probMin":0,"probMax":1,"cooldownQuarters":0,
                 "maxFires":10,"minTurn":0,"priority":1000,"enabled":true,"options":[]}
                """));
        List<EventDefinitionRepository.Definition> extended = new ArrayList<>(List.of(extra));
        extended.addAll(seeded.reversed());
        var engine = new EventEngine(null, null, validator, null, null, null, null);
        Map<String, Integer> stats = Map.of("cooking", 50, "service", 55, "management", 45, "marketing", 60,
                "stamina", 30, "network", 50);
        int fired = 0;
        for (long seed = 1; seed <= 20; seed++) {
            for (int turn = 1; turn <= 8; turn++) {
                var context = new GameContext(1, 1, seed, turn, "20232", null);
                var original = engine.judge(context, variables(turn), stats, seeded);
                var changed = engine.judge(context, variables(turn), stats, extended);
                for (var entry : original.entrySet()) {
                    assertEquals(entry.getValue(), changed.get(entry.getKey()), entry.getKey());
                    fired += entry.getValue() ? 1 : 0;
                }
            }
        }
        assertTrue(fired > 0, "비교가 의미 있도록 최소 한 번은 발생해야 한다");
    }

    private Map<String, Object> variables(int turn) {
        Map<String, Object> values = new java.util.HashMap<>(Map.ofEntries(
                Map.entry("quartersOperated", turn + 1), Map.entry("negativeQuarters", turn % 3 == 0 ? 1 : 0),
                Map.entry("quarterOfYear", turn % 4 + 1), Map.entry("cash", 30000000L - turn * 4000000L),
                Map.entry("cumulativeProfit", turn * 5000000L), Map.entry("lastProfit", turn % 2 == 0 ? 8000000L : -2000000L),
                Map.entry("lastRevenue", 50000000L), Map.entry("areaRank", 2), Map.entry("areaRankTotal", 11),
                Map.entry("areaRankPct", 2 / 11.0), Map.entry("staff", 2), Map.entry("openHours", 13),
                Map.entry("marketingBudget", 2000000L), Map.entry("priceLevel", "NORMAL"),
                Map.entry("industryCategory", "FOOD"), Map.entry("serviceCode", "CS100010"), Map.entry("shopCount", 1),
                Map.entry("rivalCount", 3), Map.entry("area.storeCount", 10), Map.entry("area.closingCount", 2),
                Map.entry("area.closingRateDelta", 3), Map.entry("area.salesTrendPct", 0.12)));
        for (String stat : OwnerCandidates.STATS) {
            values.put("stat." + stat, 50);
        }
        return values;
    }
}
