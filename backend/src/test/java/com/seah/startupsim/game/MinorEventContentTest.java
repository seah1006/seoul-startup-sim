package com.seah.startupsim.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** V14_1 작은 사건 콘텐츠: 검증기 통과, 개수, 체인, 효과 범위, DATA 결측, 초반 후보 수를 실제 마이그레이션으로 확인한다. */
@Testcontainers
class MinorEventContentTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    private static final List<String> SERVICES = List.of("CS100001", "CS100002", "CS100003", "CS100004", "CS100005",
            "CS100006", "CS100007", "CS100008", "CS100009", "CS100010");
    private static final Pattern FLAG = Pattern.compile("flag\\.(MI_F_[A-Z0-9_]+)");
    private static final Pattern SINCE = Pattern.compile("history\\.quartersSince\\.([A-Z0-9_]+)");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EventDefinitionValidator VALIDATOR = new EventDefinitionValidator();
    private static final EventEngine ENGINE = new EventEngine(null, null, VALIDATOR, null, null, null, JSON);
    private static List<EventDefinitionRepository.Definition> minors;
    private static int minorRows;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        minorRows = jdbc.queryForObject("SELECT count(*) FROM turning_points WHERE tier = 'MINOR'", Integer.class);
        // 저장소는 검증기를 통과한 정의만 돌려준다. 하나라도 빠지면 아래 개수 비교가 실패한다.
        minors = new EventDefinitionRepository(jdbc, JSON, VALIDATOR).turningPoints().stream()
                .filter(point -> EventEngine.tier(point).equals("MINOR")).toList();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turning_points WHERE tier = 'MAJOR'", Integer.class))
                .isEqualTo(12);
    }

    @Test
    void everyMinorDefinitionPassesValidator() {
        assertThat(minors).hasSize(minorRows);
        for (var point : minors) {
            VALIDATOR.turningPoint(point.data());
        }
    }

    @Test
    void countsCodesAndChains() {
        var story = minors.stream().filter(point -> EventEngine.source(point).equals("STORY")).toList();
        var data = minors.stream().filter(point -> EventEngine.source(point).equals("DATA")).toList();
        var followUps = story.stream().filter(point -> FLAG.matcher(point.data().get("conditionExpr").toString()).find()).toList();
        assertThat(story).allMatch(point -> point.code().startsWith("MI_"));
        assertThat(data).allMatch(point -> point.code().startsWith("MD_"));
        assertThat(story.size() - followUps.size()).isGreaterThanOrEqualTo(24);
        assertThat(followUps).hasSizeGreaterThanOrEqualTo(6);
        assertThat(data).hasSizeGreaterThanOrEqualTo(8);

        Map<String, Set<String>> setters = new HashMap<>();
        for (var point : minors) {
            for (JsonNode option : point.data().get("options")) {
                for (String key : List.of("onSuccess", "onFail")) {
                    for (JsonNode effect : option.get(key)) {
                        if (effect.get("type").asString().equals("FLAG") && effect.get("value").booleanValue()) {
                            setters.computeIfAbsent(effect.get("flag").asString(), flag -> new HashSet<>()).add(point.code());
                        }
                    }
                }
            }
        }
        for (var point : followUps) {
            String condition = point.data().get("conditionExpr").toString();
            Matcher flag = FLAG.matcher(condition);
            Matcher since = SINCE.matcher(condition);
            assertThat(flag.find() && since.find()).as(point.code()).isTrue();
            assertThat(setters.get(flag.group(1))).as(point.code()).containsExactly(since.group(1));
            assertThat(point.data().get("maxFires").intValue()).as(point.code()).isEqualTo(1);
            assertThat(point.data().get("priority").intValue()).as(point.code()).isGreaterThan(50);
        }
    }

    @Test
    void effectsStaySmall() {
        for (var point : minors) {
            for (JsonNode option : point.data().get("options")) {
                for (String key : List.of("onSuccess", "onFail")) {
                    for (JsonNode effect : option.get(key)) {
                        String where = point.code() + "." + key;
                        switch (effect.get("type").asString()) {
                            case "CASH" -> assertThat(Math.abs(effect.get("amount").longValue())).as(where)
                                    .isBetween(500_000L, 5_000_000L);
                            case "STAT" -> assertThat(Math.abs(effect.get("delta").intValue())).as(where).isBetween(1, 3);
                            case "MODIFIER" -> {
                                assertThat(effect.get("quarters").intValue()).as(where).isBetween(1, 3);
                                if (effect.get("mode").asString().equals("MULTIPLY")) {
                                    assertThat(effect.get("value").doubleValue()).as(where).isBetween(0.85, 1.15);
                                }
                            }
                            case "FLAG" -> {}
                            default -> throw new AssertionError(where + ": 작은 사건에 허용하지 않는 효과");
                        }
                    }
                }
            }
        }
    }

    @Test
    void dataEventsNeedRealValuesAndRenderThem() {
        Shop shop = new Shop("A", "가상 상권", "D", "CS100010", "커피-음료", "작은 가게", 0, 0, 0, 1);
        Map<String, Object> actual = new HashMap<>(Map.of("area.quarterLabel", "2023년 2분기",
                "area.salesChangePct", -12.4, "area.storeChange", -1, "area.openings", 2, "area.closings", 3,
                "area.floatingPopChangePct", 9.1));
        for (var point : minors) {
            JsonNode condition = point.data().get("conditionExpr");
            if (EventEngine.source(point).equals("DATA")) {
                assertThat(condition.toString()).as(point.code()).contains("\"area.");
                Map<String, Object> missing = early("CS100010", 2);
                assertThat(VALIDATOR.condition(condition, EventDefinitionValidator.Scope.TURNING_POINT).matches(missing))
                        .as(point.code()).isFalse();
            }
            List<String> texts = new ArrayList<>(List.of(point.data().get("title").asString(),
                    point.data().get("description").asString()));
            for (JsonNode option : point.data().get("options")) {
                texts.add(option.get("label").asString());
                texts.add(option.get("successText").asString());
                texts.add(option.get("failText").asString());
            }
            for (String text : texts) {
                String rendered = EventText.render(text, shop, actual);
                assertThat(rendered).as(point.code()).doesNotContain("{", "}", "자료 없음");
            }
            assertThat(point.data().get("description").asString().codePointCount(0,
                    point.data().get("description").asString().length())).as(point.code()).isBetween(60, 170);
        }
    }

    @Test
    void earlyQuartersAlwaysHaveSeveralStoryCandidates() {
        for (String service : SERVICES) {
            for (int quarter = 1; quarter <= 4; quarter++) {
                Map<String, Object> variables = early(service, quarter);
                long eligible = minors.stream()
                        .filter(point -> EventEngine.source(point).equals("STORY"))
                        .filter(point -> point.data().get("minTurn").intValue() <= 1)
                        .filter(point -> {
                            EventContextFactory.defaults(point.data().get("conditionExpr"), variables);
                            return VALIDATOR.condition(point.data().get("conditionExpr"),
                                    EventDefinitionValidator.Scope.TURNING_POINT).matches(variables);
                        }).count();
                assertThat(eligible).as(service + " Q" + quarter).isGreaterThanOrEqualTo(8);
            }
        }
    }

    @Test
    void pickerSpreadsStoriesAndPrefersDueFollowUp() {
        Map<String, Double> settings = Map.of("event.minor.enabled", 1d, "event.minor.rate", 1d,
                "event.minor.dataFirst", 1d, "event.minor.dataMax", 4d);
        Map<String, Integer> stats = Map.of("cooking", 40, "service", 40, "management", 40, "marketing", 40,
                "stamina", 40, "network", 40);
        Set<String> picked = new HashSet<>();
        for (String service : SERVICES) {
            for (long seed = 1; seed <= 30; seed++) {
                var point = ENGINE.pickMinor(new GameContext(1, 1, seed, 1, "20232", null), early(service, 2), stats,
                        minors, settings);
                assertThat(point).as(service + " seed " + seed).isNotNull();
                assertThat(EventEngine.source(point)).isEqualTo("STORY");
                picked.add(point.code());
            }
        }
        assertThat(picked).hasSizeGreaterThanOrEqualTo(15);

        Map<String, Object> due = early("CS100009", 1);
        due.put("quartersOperated", 5);
        due.put("flag.MI_F_CREDIT_OPEN", true);
        due.put("history.timesFired.MI_CREDIT_TAB", 1);
        due.put("history.quartersSince.MI_CREDIT_TAB", 2);
        assertThat(ENGINE.pickMinor(new GameContext(1, 1, 7, 4, "20241", null), due, stats, minors, settings).code())
                .isEqualTo("MI_CREDIT_RETURN");

        Map<String, Object> slump = early("CS100009", 1);
        slump.put("area.salesChangePct", -15.0);
        assertThat(ENGINE.pickMinor(new GameContext(1, 1, 7, 1, "20241", null), slump, stats, minors, settings).code())
                .isEqualTo("MD_SALES_SLUMP");
    }

    /** 2분기째(turn 1) 표준 정책 게임의 조건 변수. DATA 변수는 넣지 않는다(결측). */
    private static Map<String, Object> early(String service, int quarterOfYear) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String stat : List.of("cooking", "service", "management", "marketing", "stamina", "network")) {
            values.put("stat." + stat, 40);
        }
        values.put("quartersOperated", 2);
        values.put("negativeQuarters", 0);
        values.put("quarterOfYear", quarterOfYear);
        values.put("cash", 30_000_000L);
        values.put("cumulativeProfit", 2_000_000L);
        values.put("lastProfit", 1_000_000L);
        values.put("staff", 2);
        values.put("openHours", 11);
        values.put("marketingBudget", 500_000L);
        values.put("priceLevel", "NORMAL");
        values.put("serviceCode", service);
        values.put("industryCategory", "FOOD");
        values.put("shopCount", 1);
        values.put("area.quarterLabel", "2023년 2분기");
        return values;
    }
}
