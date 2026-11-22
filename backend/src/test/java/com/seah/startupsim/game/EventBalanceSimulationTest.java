package com.seah.startupsim.game;

import com.seah.startupsim.game.GameTypes.Activity;
import com.seah.startupsim.game.GameTypes.Decision;
import com.seah.startupsim.game.GameTypes.Price;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 이벤트 밸런스 시뮬레이터.
 *
 * <p>메인 DB에는 읽기 전용 세션의 SELECT만 실행한다. 전환점·순간·동네 반응 판정은 실제 엔진과 같은 난수 스트림
 * ({@link EventRandom}), 같은 발생 판정({@link EventEngine#judge}), 같은 DSL 검증기로 메모리에서 재현하고,
 * DB 쓰기 대신 현금·능력치·보정·플래그·가상 경쟁점 수를 메모리 상태로 들고 간다.
 * 조정 후 값은 {@code V12__event_tuning.sql}의 UPDATE 문을 그대로 읽어 적용하므로 시뮬레이터와 마이그레이션이 어긋나지 않는다.
 */
@Tag("simulation")
class EventBalanceSimulationTest {
    private static final Path REPORT = Path.of("../docs/balance/EVENT_BALANCE_REPORT.md");
    private static final Path V12 = Path.of("src/main/resources/db/migration/V12__event_tuning.sql");
    private static final String BEGIN = "<!-- simulation:begin -->";
    private static final String END = "<!-- simulation:end -->";
    private static final int TURNS = 12;
    private static final int AREAS = 200;
    private static final Decision STANDARD = new Decision(Price.NORMAL, 10, 2, 1000000, Activity.STUDY);
    private static final Map<String, String> COLUMNS = Map.of("base_probability", "baseProbability",
            "cooldown_quarters", "cooldownQuarters", "max_fires", "maxFires", "min_turn", "minTurn",
            "prob_min", "probMin", "prob_max", "probMax", "priority", "priority");
    private static final String OPENED = "OPENED";
    private static final String CLOSED = "CLOSED";

    private final ObjectMapper json = new ObjectMapper();
    private final EventDefinitionValidator validator = new EventDefinitionValidator();
    private final EventEngine engine = new EventEngine(null, null, validator, null, null, null, null);

    record Market(String area, String service, String quarter, long sales, int stores, String district, double size,
            String type, Map<String, Object> areaValues) {}

    record Definitions(List<EventDefinitionRepository.Definition> points,
            List<EventDefinitionRepository.Definition> reactions, List<EventDefinitionRepository.Definition> moments,
            Map<String, Double> settings) {}

    record Fire(int turn, String code, int order, double probability, int ratePct, boolean success) {}

    record Outcome(String service, boolean survived, int turns, List<Fire> fires, int moments, int momentSuccesses,
            List<Integer> reactions, Set<String> missionsAchieved, int bestRank) {}

    /** 게임 하나의 메모리 상태. DB의 games·owners·game_modifiers·game_flags·rivals·game_turning_points 대응. */
    static final class State {
        long cash;
        int negatives;
        int blackStreak;
        int bestRank = Integer.MAX_VALUE;
        final Set<String> missionsAchieved = new HashSet<>();
        int shopCount = 1;
        int virtualRivals;
        long cumulativeProfit;
        long[] last;
        final Map<String, Integer> stats = new LinkedHashMap<>();
        final List<double[]> modifierWindows = new ArrayList<>();
        final List<QuarterCalculator.Modifier> modifiers = new ArrayList<>();
        final Set<String> flags = new HashSet<>();
        final Map<String, int[]> history = new HashMap<>();
        String lastCode = "NONE";
        String lastResult = "NONE";
        int lastTurn = -1;
    }

    @Test
    void simulate() throws Exception {
        Properties env = new Properties();
        try (var input = Files.newInputStream(Path.of("../.env"))) {
            env.load(input);
        }
        Map<String, Double> settings = new HashMap<>();
        Map<String, String> categories = new HashMap<>();
        Map<String, Market> markets = new HashMap<>();
        Map<String, List<String>> areas = new TreeMap<>();
        List<EventDefinitionRepository.Definition> points;
        List<EventDefinitionRepository.Definition> reactions;
        List<EventDefinitionRepository.Definition> moments;
        try (Connection connection = DriverManager.getConnection(System.getProperty("simulation.url"),
                env.getProperty("DB_USERNAME"), env.getProperty("DB_PASSWORD"))) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET default_transaction_read_only = on");
                try (ResultSet rs = statement.executeQuery("SHOW default_transaction_read_only")) {
                    rs.next();
                    assertEquals("on", rs.getString(1));
                }
                try (ResultSet rs = statement.executeQuery("SELECT key, value::text FROM game_settings")) {
                    while (rs.next()) {
                        settings.put(rs.getString(1), rs.getString(2).equals("true") ? 1d : rs.getString(2).equals("false") ? 0d : Double.valueOf(rs.getString(2)));
                    }
                }
                try (ResultSet rs = statement.executeQuery(
                        "SELECT service_code, category FROM service_industries WHERE service_code LIKE 'CS1%'")) {
                    while (rs.next()) {
                        categories.put(rs.getString(1), rs.getString(2));
                    }
                }
                try (ResultSet rs = statement.executeQuery("""
                        SELECT s.area_code, s.service_code, s.quarter, s.sales_amount,
                               coalesce(t.store_count, 0), a.district_code, coalesce(a.area_m2, 1), a.area_type_name,
                               t.store_count, t.franchise_count, t.opening_count, t.closing_count,
                               t.opening_rate, t.closing_rate, f.total_population
                        FROM area_sales_q_active s
                        JOIN trade_areas a USING (area_code)
                        LEFT JOIN area_stores_q_active t USING (area_code, service_code, quarter)
                        LEFT JOIN area_floating_pop_q_active f USING (area_code, quarter)
                        WHERE s.service_code LIKE 'CS1%' AND s.sales_amount IS NOT NULL
                        ORDER BY s.service_code, s.area_code, s.quarter
                        """)) {
                    String[] names = {"storeCount", "franchiseCount", "openingCount", "closingCount", "openingRate",
                            "closingRate", "floatingPopulation"};
                    while (rs.next()) {
                        Map<String, Object> values = new HashMap<>();
                        for (int index = 0; index < names.length; index++) {
                            Object value = rs.getObject(9 + index);
                            if (value != null) {
                                values.put(names[index], ((Number) value).doubleValue());
                            }
                        }
                        Market market = new Market(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                                rs.getInt(5), rs.getString(6), rs.getDouble(7), rs.getString(8), values);
                        markets.put(key(market.service(), market.area(), market.quarter()), market);
                        List<String> list = areas.computeIfAbsent(market.service(), service -> new ArrayList<>());
                        if (list.isEmpty() || !list.getLast().equals(market.area())) {
                            list.add(market.area());
                        }
                    }
                }
                points = load(statement, EventDefinitionRepository.Kind.TURNING_POINT, validator::turningPoint);
                reactions = load(statement, EventDefinitionRepository.Kind.REACTION, validator::reaction);
                moments = load(statement, EventDefinitionRepository.Kind.MOMENT, validator::moment);
            }
        }
        sample(areas, markets);
        if (Boolean.getBoolean("simulation.v14")) {
            try (var db = new org.testcontainers.containers.PostgreSQLContainer<>(
                    org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"))) {
                db.start();
                org.flywaydb.core.Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load().migrate();
                var jdbc = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        db.getJdbcUrl(), db.getUsername(), db.getPassword()));
                var repository = new EventDefinitionRepository(jdbc, json, validator);
                jdbc.query("SELECT key, value::text FROM game_settings WHERE key LIKE 'event.%'", row -> {
                    String value = row.getString(2);
                    settings.put(row.getString(1), value.equals("true") ? 1d : value.equals("false") ? 0d : Double.valueOf(value));
                });
                var current = new Definitions(repository.turningPoints(), repository.reactions(), repository.moments(), settings);
                reportV14(run(current, areas, markets, categories), current);
            }
            return;
        }
        Definitions before = new Definitions(points, reactions, moments, settings);
        Definitions after = tuned(before);
        Definitions withGap = tuned(after, Path.of("src/main/resources/db/migration/V13__event_global_gap.sql"));
        List<Outcome> beforeOutcomes = run(before, areas, markets, categories);
        List<Outcome> afterOutcomes = run(after, areas, markets, categories);

        StringBuilder report = new StringBuilder();
        report.append("측정: ").append(Instant.now()).append("\n\n")
                .append("고정 시드 404, 외식 10업종 × 업종별 무작위 ").append(AREAS)
                .append("상권 × 2021~2023의 11개 시작 분기 = 업종당 ").append(AREAS * 11).append("게임, 각 최대 ")
                .append(TURNS).append("분기. 표준 정책(NORMAL/10시간/직원2/마케팅100만원/공부). ")
                .append("사장님은 게임 시드로 배경 4종 중 하나와 첫 후보 능력치를 뽑고, 집중 능력치는 가장 높은 둘. ")
                .append("전환점 선택지는 게임별 고정 시드로 균등 선택. 순간·동네 반응·가상 경쟁점·전환점 효과(현금·능력치·보정·")
                .append("플래그·매장 수·경쟁점)를 모두 메모리 상태에 반영. 실제 경쟁점 수는 상권 점포 수로 기대한 반경 내 수와 같다고 ")
                .append("가정(밀집 보정 1에서 시작). 마지막 분기(종료 분기)에는 엔진과 같이 전환점이 뜨지 않는다.\n\n");
        List<Outcome> gapOutcomes = run(withGap, areas, markets, categories);
        compare(report, beforeOutcomes, afterOutcomes);
        compareGap(report, afterOutcomes, gapOutcomes);
        distribution(report, beforeOutcomes, afterOutcomes);
        perPoint(report, before, beforeOutcomes, afterOutcomes, after);
        perOption(report, "조정 전", beforeOutcomes);
        perOption(report, "조정 후 (V12)", afterOutcomes);
        write(report.toString());
        System.out.println(report);
    }

    private void reportV14(List<Outcome> outcomes, Definitions definitions) throws Exception {
        var completed = outcomes.stream().filter(o -> o.turns() == 12 && o.survived()).toList();
        var tier = definitions.points().stream().collect(java.util.stream.Collectors.toMap(EventDefinitionRepository.Definition::code, EventEngine::tier));
        var source = definitions.points().stream().collect(java.util.stream.Collectors.toMap(EventDefinitionRepository.Definition::code, EventEngine::source));
        long quarters = completed.size() * 12L;
        long events = completed.stream().mapToLong(o -> o.fires().size()).sum();
        StringBuilder report = new StringBuilder("\n\n## V14 콘텐츠 포함 실측\n\n측정: " + Instant.now() + "\n\n");
        report.append("표준 정책, 고정 시드404, ").append(outcomes.size()).append("게임, 완주 ").append(completed.size()).append("게임. MINOR는 기대효과 봇, MAJOR는 기존 균등 정책.\n\n");
        report.append(String.format(Locale.ROOT, "전체 생존율 %.2f%%. 완주 기준 선택 없는 분기 %.2f%% (종료 분기 포함), 게임당 사건 %.3f회.\n\n",
                100.0 * outcomes.stream().filter(Outcome::survived).count() / outcomes.size(), 100.0 * (quarters-events)/quarters, (double)events/completed.size()));
        for (String kind : List.of("MAJOR", "MINOR", "DATA")) {
            long count = completed.stream().flatMap(o -> o.fires().stream()).filter(f -> kind.equals("DATA") ? source.get(f.code()).equals("DATA") : tier.get(f.code()).equals(kind)).count();
            report.append(String.format(Locale.ROOT, "- 게임당 %s: %.3f회\n", kind, (double)count/completed.size()));
        }
        report.append("\n| 업종 | 게임 수 | 생존율 |\n| --- | ---: | ---: |\n");
        for (String service : outcomes.stream().map(Outcome::service).distinct().sorted().toList()) {
            var rows = outcomes.stream().filter(o -> o.service().equals(service)).toList();
            report.append(String.format(Locale.ROOT,"| %s | %d | %.2f%% |\n", service, rows.size(),100.0*rows.stream().filter(Outcome::survived).count()/rows.size()));
        }
        Files.writeString(REPORT, Files.readString(REPORT) + report);
        StringBuilder missions = new StringBuilder("# 목표 달성률 (V14)\n\n" + report + "\n모든 목표는 같은 표준 정책 표본에 사후 판정했습니다. 목표별 최적화 봇이 아닙니다. 분모는 중도 폐업을 포함한 전체 게임입니다.\n\n| 목표 | 달성률 |\n| --- | ---: |\n");
        for (Mission mission : Mission.values()) {
            double rate = 100.0*outcomes.stream().filter(o -> o.missionsAchieved().contains(mission.name())).count()/outcomes.size();
            missions.append(String.format(Locale.ROOT,"| %s | %.2f%% |\n",mission.name(),rate));
        }
        missions.append("\n지역 목표 임계값 비교(동일 표본·목표는 봇 정책에 영향 없음):\n");
        for (int threshold : List.of(5, 10, 15, 20, 25, 30)) {
            missions.append(String.format(Locale.ROOT, "- %d위 이내: %.2f%%\n", threshold,
                    100.0 * outcomes.stream().filter(o -> o.bestRank() <= threshold).count() / outcomes.size()));
        }
        Files.writeString(Path.of("../docs/balance/MISSION_REPORT.md"), missions);
        System.out.println(report); System.out.println(missions);
    }
    private List<EventDefinitionRepository.Definition> load(Statement statement, EventDefinitionRepository.Kind kind,
            java.util.function.Consumer<JsonNode> validation) throws Exception {
        String sql = switch (kind) {
            case TURNING_POINT -> """
                    SELECT p.id, p.rev, jsonb_build_object(
                        'code', p.code, 'title', p.title, 'description', p.description,
                        'tier', coalesce(to_jsonb(p)->>'tier', 'MAJOR'), 'source', coalesce(to_jsonb(p)->>'source', 'STORY'),
                        'conditionExpr', p.condition_expr, 'baseProbability', p.base_probability,
                        'statBonus', p.stat_bonus, 'probMin', p.prob_min, 'probMax', p.prob_max,
                        'cooldownQuarters', p.cooldown_quarters, 'maxFires', p.max_fires,
                        'minTurn', p.min_turn, 'priority', p.priority, 'enabled', p.enabled,
                        'options', (SELECT jsonb_agg(jsonb_build_object(
                            'orderNo', o.order_no, 'label', o.label, 'successFormula', o.success_formula,
                            'onSuccess', o.on_success, 'onFail', o.on_fail,
                            'successText', o.success_text, 'failText', o.fail_text) ORDER BY o.order_no)
                            FROM turning_point_options o WHERE o.turning_point_id = p.id))::text
                    FROM turning_points p WHERE p.enabled ORDER BY p.priority DESC, p.code
                    """;
            case REACTION -> """
                    SELECT id, rev, jsonb_build_object('code', code, 'kind', kind, 'author', author,
                        'rating', rating, 'weight', weight, 'enabled', enabled,
                        'conditionExpr', condition_expr, 'text', body)::text
                    FROM reaction_templates WHERE enabled ORDER BY code
                    """;
            case MOMENT -> """
                    SELECT id, rev, jsonb_build_object('code', code, 'title', title, 'stat', stat,
                        'difficulty', difficulty, 'weight', weight, 'enabled', enabled,
                        'conditionExpr', condition_expr, 'successText', success_text,
                        'failText', fail_text, 'onSuccess', on_success, 'onFail', on_fail)::text
                    FROM moment_templates WHERE enabled ORDER BY code
                    """;
        };
        List<EventDefinitionRepository.Definition> definitions = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                JsonNode data = json.readTree(rs.getString(3));
                validation.accept(data);
                definitions.add(new EventDefinitionRepository.Definition(rs.getLong(1), rs.getInt(2), data));
            }
        }
        return List.copyOf(definitions);
    }

    /** V12의 전환점 UPDATE(숫자 열·조건식)와 이벤트 설정 UPDATE를 메모리 정의에 그대로 적용한다. */
    private Definitions tuned(Definitions before) throws Exception {
        return tuned(before, V12);
    }

    private Definitions tuned(Definitions before, Path migration) throws Exception {
        if (!Files.exists(migration)) {
            return before;
        }
        String sql = Files.readString(migration);
        Map<String, ObjectNode> changed = new LinkedHashMap<>();
        for (var point : before.points()) {
            changed.put(point.code(), (ObjectNode) point.data().deepCopy());
        }
        Matcher update = Pattern.compile("UPDATE turning_points SET ([^;]*?) WHERE code = '([A-Z0-9_]+)';").matcher(sql);
        while (update.find()) {
            ObjectNode node = changed.get(update.group(2));
            String assignments = update.group(1);
            Matcher condition = Pattern.compile("condition_expr = '(.*?)'::jsonb", Pattern.DOTALL).matcher(assignments);
            if (condition.find()) {
                node.set("conditionExpr", json.readTree(condition.group(1)));
            }
            Matcher number = Pattern.compile("\\b([a-z_]+) = ([0-9.]+)\\b").matcher(assignments);
            while (number.find()) {
                String field = COLUMNS.get(number.group(1));
                if (field != null) {
                    if (number.group(2).contains(".")) {
                        node.put(field, Double.parseDouble(number.group(2)));
                    } else {
                        node.put(field, Integer.parseInt(number.group(2)));
                    }
                }
            }
            validator.turningPoint(node);
        }
        List<EventDefinitionRepository.Definition> points = new ArrayList<>();
        for (var point : before.points()) {
            points.add(new EventDefinitionRepository.Definition(point.id(), point.rev() + 1, changed.get(point.code())));
        }
        points.sort(Comparator.comparingInt((EventDefinitionRepository.Definition point) ->
                -point.data().get("priority").intValue()).thenComparing(EventDefinitionRepository.Definition::code));
        Map<String, Double> settings = new HashMap<>(before.settings());
        Matcher setting = Pattern.compile("\\('(event\\.[a-zA-Z.]+)',\\s*'([0-9.]+)'\\)").matcher(sql);
        while (setting.find()) {
            settings.put(setting.group(1), Double.valueOf(setting.group(2)));
        }
        return new Definitions(List.copyOf(points), before.reactions(), before.moments(), settings);
    }

    private static String key(String service, String area, String quarter) {
        return service + ":" + area + ":" + quarter;
    }

    private static String quarter(int index) {
        return String.valueOf(index / 4) + (index % 4 + 1);
    }

    /** 밸런스 시뮬레이터와 같은 표본: 11개 시작 분기 모두 12턴 실측 매출이 있는 상권 중 업종별 200곳. */
    private static void sample(Map<String, List<String>> areas, Map<String, Market> markets) {
        for (var entry : areas.entrySet()) {
            entry.getValue().removeIf(area -> {
                for (int index = 2021 * 4; index < 2023 * 4 + 2 + TURNS; index++) {
                    if (!markets.containsKey(key(entry.getKey(), area, quarter(index)))) {
                        return true;
                    }
                }
                return false;
            });
            Collections.shuffle(entry.getValue(), new Random(404));
            entry.setValue(new ArrayList<>(entry.getValue().subList(0, Math.min(AREAS, entry.getValue().size()))));
        }
    }

    private List<Outcome> run(Definitions definitions, Map<String, List<String>> areas, Map<String, Market> markets,
            Map<String, String> categories) {
        List<Outcome> outcomes = new ArrayList<>();
        QuarterCalculator calculator = new QuarterCalculator();
        for (String service : areas.keySet()) {
            for (String area : areas.get(service)) {
                for (int start = 2021 * 4; start <= 2023 * 4 + 2; start++) {
                    outcomes.add(play(calculator, definitions, markets, categories.getOrDefault(service, "FOOD"),
                            service, area, start));
                }
            }
        }
        return outcomes;
    }

    private Outcome play(QuarterCalculator calculator, Definitions definitions, Map<String, Market> markets,
            String category, String service, String area, int start) {
        long seed = EventRandom.mix64(404L ^ EventRandom.hash64(service + "|" + area + "|" + start));
        GameTypes.Background background = GameTypes.Background.values()[(int) Math.floorMod(seed, 4L)];
        State state = new State();
        state.stats.putAll(new OwnerCandidates().generate(background, seed).getFirst().stats());
        List<String> focus = state.stats.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(2).map(Map.Entry::getKey).toList();
        SplittableRandom chooser = new SplittableRandom(seed ^ 0x5EEDC401CEL);
        Map<String, Double> settings = definitions.settings();
        state.cash = settings.get("initialCapital").longValue();
        Market first = markets.get(key(service, area, quarter(start)));
        double expected = Math.max(1, Math.max(0, first.stores())
                * Math.min(1, Math.PI * 300 * 300 / Math.max(1, first.size())));
        int realRivals = (int) Math.round(expected);
        List<Fire> fires = new ArrayList<>();
        List<Integer> reactionCounts = new ArrayList<>();
        int momentCount = 0;
        int momentSuccesses = 0;
        int turn = 0;
        String ending = null;
        for (; turn < TURNS && ending == null; turn++) {
            Market market = markets.get(key(service, area, quarter(start + turn)));
            var input = new QuarterCalculator.Input(seed, turn, market.quarter(), state.cash, market.sales(), 0,
                    market.stores(), realRivals + state.virtualRivals, market.size(),
                    number(market.areaValues().get("openingCount")), number(market.areaValues().get("closingCount")),
                    Map.copyOf(state.stats), focus, STANDARD, settings, market.district(), service,
                    active(state, turn));
            QuarterOutcome outcome = calculator.calculate(input);
            state.bestRank = Math.min(state.bestRank, outcome.areaRank().rank());
            state.cumulativeProfit += outcome.profit();
            state.blackStreak = outcome.profit() > 0 ? state.blackStreak + 1 : 0;
            for (Mission mission : Mission.values()) {
                long progress = switch (mission) {
                    case SURVIVE_3Y -> turn + 1;
                    case PROFIT_100M -> state.cumulativeProfit;
                    case AREA_TOP3 -> outcome.areaRank().rank();
                    case BLACK_4 -> state.blackStreak;
                    case SECOND_SHOP -> state.shopCount;
                };
                if (mission.achieved(progress)) state.missionsAchieved.add(mission.name());
            }
            state.cash = outcome.cashAfter();
            for (var change : outcome.statChanges()) {
                state.stats.put(change.stat(), change.after());
            }
            state.negatives = state.cash < 0 ? state.negatives + 1 : 0;
            ending = state.negatives >= 2 ? "CLOSED" : turn == TURNS - 1 ? "LONG_RUNNING" : null;
            GameContext context = new GameContext(1, 1, seed, turn, market.quarter(), ending);
            Map<String, Object> variables = variables(state, context, outcome, market, markets, category, service,
                    realRivals);
            if (!definitions.moments().isEmpty()) {
                Boolean success = moment(state, context, variables, definitions);
                if (success != null) {
                    momentCount++;
                    momentSuccesses += success ? 1 : 0;
                }
            }
            String news = definitions.reactions().isEmpty() ? null : rivals(state, context, settings, variables);
            variables = variables(state, context, outcome, market, markets, category, service, realRivals);
            if (!definitions.reactions().isEmpty()) {
                reactionCounts.add(reactions(state, context, outcome, variables, definitions, news));
            }
            if (ending == null && !definitions.points().isEmpty()
) {
                Fire fire = turningPoint(state, context, variables, definitions.points(), chooser, settings);
                if (fire != null) {
                    fires.add(fire);
                }
            }
            state.last = new long[] {outcome.profit(), outcome.visitors(), outcome.areaRank().rank()};
        }
        return new Outcome(service, !"CLOSED".equals(ending), turn, fires, momentCount, momentSuccesses,
                reactionCounts, Set.copyOf(state.missionsAchieved), state.bestRank);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    /** {@link GameModifierRepository#active}: 시작 포함·종료 제외, target·mode·id 순. */
    private static List<QuarterCalculator.Modifier> active(State state, int turn) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < state.modifiers.size(); index++) {
            double[] window = state.modifierWindows.get(index);
            if (window[0] <= turn && turn < window[1]) {
                indexes.add(index);
            }
        }
        indexes.sort(Comparator.comparing((Integer index) -> state.modifiers.get(index).target())
                .thenComparing(index -> state.modifiers.get(index).mode()).thenComparingInt(index -> index));
        return indexes.stream().map(state.modifiers::get).toList();
    }

    /** {@link EventContextFactory#variables}와 같은 이름·값. 결측은 넣지 않는다. */
    private static Map<String, Object> variables(State state, GameContext context, QuarterOutcome outcome,
            Market market, Map<String, Market> markets, String category, String service, int realRivals) {
        Map<String, Object> values = new HashMap<>();
        state.stats.forEach((stat, value) -> values.put("stat." + stat, value));
        values.put("quartersOperated", context.turn() + 1);
        values.put("negativeQuarters", state.negatives);
        values.put("quarterOfYear", Integer.parseInt(context.quarter().substring(4)));
        values.put("cash", state.cash);
        values.put("cumulativeProfit", state.cumulativeProfit);
        values.put("lastProfit", outcome.profit());
        values.put("lastRevenue", outcome.revenue());
        values.put("areaRank", outcome.areaRank().rank());
        values.put("areaRankTotal", outcome.areaRank().of());
        values.put("areaRankPct", outcome.areaRank().rank() / (double) outcome.areaRank().of());
        values.put("staff", STANDARD.staff());
        values.put("openHours", STANDARD.openHours());
        values.put("marketingBudget", STANDARD.marketingBudget());
        values.put("priceLevel", STANDARD.priceLevel().name());
        values.put("serviceCode", service);
        values.put("rivalCount", (long) realRivals + state.virtualRivals);
        values.put("shopCount", state.shopCount);
        values.put("industryCategory", category);
        if (market.type() != null) {
            values.put("area.areaType", market.type());
        }
        market.areaValues().forEach((name, value) -> values.put("area." + name, value));
        Market previous = markets.get(key(service, market.area(), GameRepository.quarter(market.quarter(), -1)));
        if (market.areaValues().get("closingRate") instanceof Double current && previous != null
                && previous.areaValues().get("closingRate") instanceof Double before) {
            values.put("area.closingRateDelta", current - before);
        }
        values.put("area.quarterLabel", market.quarter().substring(0, 4) + "년 " + market.quarter().substring(4) + "분기");
        Map.of("openingCount", "openings", "closingCount", "closings", "openingRate", "openingRatePct", "closingRate", "closingRatePct")
                .forEach((source, target) -> { if (market.areaValues().containsKey(source)) values.put("area." + target, market.areaValues().get(source)); });
        if (previous != null) {
            EventContextFactory.change(values, "area.salesChangePct", market.sales(), previous.sales());
            EventContextFactory.change(values, "area.floatingPopChangePct", (Number) market.areaValues().get("floatingPopulation"),
                    (Number) previous.areaValues().get("floatingPopulation"));
            Map.of("storeCount", "storeChange", "franchiseCount", "franchiseChange").forEach((source, target) -> {
                if (market.areaValues().get(source) instanceof Number current && previous.areaValues().get(source) instanceof Number before)
                    values.put("area." + target, current.doubleValue() - before.doubleValue());
            });
        }
        Market yearAgo = markets.get(key(service, market.area(), GameRepository.quarter(market.quarter(), -4)));
        if (yearAgo != null && market.sales() > 0 && yearAgo.sales() > 0) {
            values.put("area.salesTrendPct", market.sales() / (double) yearAgo.sales() - 1);
        }
        for (String flag : state.flags) {
            values.put("flag." + flag, true);
        }
        state.history.forEach((code, history) -> {
            values.put("history.timesFired." + code, history[0]);
            values.put("history.quartersSince." + code, context.turn() - history[1]);
        });
        return values;
    }

    private boolean matches(JsonNode condition, EventDefinitionValidator.Scope scope, Map<String, Object> variables) {
        EventContextFactory.defaults(condition, variables);
        return validator.condition(condition, scope).matches(variables);
    }

    /** {@link NeighborhoodEventService#moment}: 가중 추첨, 능력치 판정, 작은 효과 적용. 순간이 없으면 null. */
    private Boolean moment(State state, GameContext context, Map<String, Object> variables, Definitions definitions) {
        List<EventDefinitionRepository.Definition> candidates = new ArrayList<>();
        for (var definition : definitions.moments()) {
            if (matches(definition.data().get("conditionExpr"), EventDefinitionValidator.Scope.MOMENT, variables)) {
                candidates.add(definition);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        double draw = EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.MOMENT_PICK, "")
                .nextDouble();
        var picked = candidates.get(NeighborhoodEventService.weighted(candidates, draw));
        JsonNode data = picked.data();
        Map<String, Double> settings = definitions.settings();
        double rate = 0.5 + (state.stats.get(data.get("stat").asString()) - data.get("difficulty").intValue())
                * settings.getOrDefault("event.moment.statSlope", 0.02);
        rate = Math.max(settings.getOrDefault("event.moment.minRate", 0.05),
                Math.min(settings.getOrDefault("event.moment.maxRate", 0.95), rate));
        boolean success = EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.MOMENT_JUDGE,
                picked.code()).nextDouble() < rate;
        String key = success ? "onSuccess" : "onFail";
        apply(state, context.turn(), validator.effects(data.get(key), true, key));
        return success;
    }

    /** {@link NeighborhoodEventService#rivals}: 가상 경쟁점 수만 메모리에서 늘리고 줄인다. */
    private static String rivals(State state, GameContext context, Map<String, Double> settings,
            Map<String, Object> variables) {
        if (!(variables.get("area.storeCount") instanceof Number stores)) {
            return null;
        }
        long storeCount = Math.max(1, stores.longValue());
        long openings = number(variables.get("area.openingCount"));
        long closings = number(variables.get("area.closingCount"));
        String news = null;
        if (openings >= 1) {
            double rate = Math.min(0.6, openings / (double) storeCount);
            int cap = settings.getOrDefault("event.rival.virtualPerQuarterCap", 2d).intValue();
            SplittableRandom random = EventRandom.stream(context.seed(), context.turn(),
                    EventRandom.Purpose.VIRTUAL_RIVAL, "");
            for (int index = 0; index < cap && random.nextDouble() < rate; index++) {
                state.virtualRivals++;
                news = OPENED;
            }
        }
        if (closings >= 1 && EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.RIVAL_CLOSURE, "")
                .nextDouble() < Math.min(0.5, closings / (double) storeCount) && state.virtualRivals > 0) {
            state.virtualRivals--;
            news = news == null ? CLOSED : news;
        }
        return news;
    }

    /** {@link NeighborhoodEventService#reactions}의 개수 규칙만 재현한다(문구 선택은 개수에 영향이 없다). */
    private int reactions(State state, GameContext context, QuarterOutcome outcome, Map<String, Object> base,
            Definitions definitions, String news) {
        Map<String, Object> values = new HashMap<>(base);
        values.put("bucket.profit", outcome.profit() < 0 ? "LOSS" : state.last == null ? "FLAT"
                : trend(outcome.profit(), state.last[0]));
        values.put("bucket.visitors", state.last == null ? "FLAT" : trend(outcome.visitors(), state.last[1]));
        values.put("bucket.rank", state.last == null || outcome.areaRank().rank() == state.last[2] ? "SAME"
                : outcome.areaRank().rank() < state.last[2] ? "UP" : "DOWN");
        values.put("bucket.price", base.get("priceLevel"));
        int hours = ((Number) base.get("openHours")).intValue();
        values.put("bucket.hours", hours <= 8 ? "SHORT" : hours >= 13 ? "LONG" : "NORMAL");
        values.put("bucket.firstQuarter", context.turn() == 0);
        values.put("lastResolved.code", state.lastCode);
        values.put("lastResolved.result", state.lastResult);
        values.put("lastResolved.quartersSince", state.lastTurn < 0 ? 999 : context.turn() - state.lastTurn);
        int candidates = 0;
        for (var definition : definitions.reactions()) {
            if (matches(definition.data().get("conditionExpr"), EventDefinitionValidator.Scope.REACTION, values)) {
                candidates++;
            }
        }
        if (candidates == 0 && news == null) {
            return 0;
        }
        int min = definitions.settings().getOrDefault("event.reaction.min", 2d).intValue();
        int max = definitions.settings().getOrDefault("event.reaction.max", 3d).intValue();
        int count = min + EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.REACTION_PICK, "")
                .nextInt(Math.max(1, max - min + 1));
        int slots = news == null ? count : count - 1;
        return Math.min(slots, candidates) + (news == null ? 0 : 1);
    }

    private static String trend(long current, long previous) {
        long difference = current - previous;
        double threshold = Math.abs(previous) * 0.1;
        if (difference > 0 && difference >= threshold) {
            return "UP";
        }
        if (difference < 0 && -difference >= threshold) {
            return "DOWN";
        }
        return "FLAT";
    }

    /**
     * {@link EventEngine}의 발생 판정과 후보 선택(가장 높은 priority 묶음에서 균등 추첨), 이어서
     * {@link TurningPointService#choose}의 성공 판정과 효과 적용. 선택지는 게임별 시드로 균등하게 고른다.
     */
    private Fire turningPoint(State state, GameContext context, Map<String, Object> variables,
            List<EventDefinitionRepository.Definition> points, SplittableRandom chooser, Map<String, Double> settings) {
        Map<String, Integer> stats = Map.copyOf(state.stats);
        var majorVariables = new HashMap<>(variables);
        points.stream().filter(point -> EventEngine.tier(point).equals("MINOR"))
                .forEach(point -> majorVariables.remove("history.quartersSince." + point.code()));
        Map<String, Boolean> judged = engine.judge(context, majorVariables, stats,
                points.stream().filter(point -> EventEngine.tier(point).equals("MAJOR"))
                    .filter(point -> context.turn() >= settings.getOrDefault("event.fire.minTurn", 1d)).toList(),
                settings.getOrDefault("event.fire.globalGap", 0d));
        List<EventDefinitionRepository.Definition> candidates = new ArrayList<>();
        int highest = -1;
        for (var point : points) {
            if (!judged.getOrDefault(point.code(), false)) {
                continue;
            }
            int priority = point.data().get("priority").intValue();
            if (candidates.isEmpty()) {
                highest = priority;
            }
            if (priority == highest) {
                candidates.add(point);
            }
        }
        if (candidates.isEmpty()) {
            var minor = engine.pickMinor(context, variables, stats, points, settings);
            if (minor == null) return null;
            candidates.add(minor);
        }
        int picked = candidates.size() == 1 ? 0 : (int) (EventRandom.stream(context.seed(), context.turn(),
                EventRandom.Purpose.TURNING_POINT_PICK, "").nextDouble() * candidates.size());
        var definition = candidates.get(picked);
        JsonNode options = definition.data().get("options");
        JsonNode option = EventEngine.tier(definition).equals("MINOR")
                ? MinorBotPolicy.choose(options, stats, validator) : options.get(chooser.nextInt(options.size()));
        int order = option.get("orderNo").intValue();
        double probability = validator.formula(option.get("successFormula")).probability(stats);
        boolean success = EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.TURNING_POINT_SUCCESS,
                definition.code() + "#" + order).nextDouble() < probability;
        String key = success ? "onSuccess" : "onFail";
        apply(state, context.turn(), validator.effects(option.get(key), false, key));
        int[] history = state.history.computeIfAbsent(definition.code(), code -> new int[2]);
        history[0]++;
        history[1] = context.turn();
        state.lastCode = definition.code();
        state.lastResult = success ? "SUCCESS" : "FAIL";
        state.lastTurn = context.turn();
        return new Fire(context.turn(), definition.code(), order, probability, (int) Math.round(probability * 100),
                success);
    }

    /** {@link EventEffectService#apply}의 메모리판. */
    private static void apply(State state, int turn, List<EventDsl.Effect> effects) {
        for (EventDsl.Effect effect : effects) {
            switch (effect) {
                case EventDsl.Cash cash -> state.cash += cash.amount();
                case EventDsl.Stat stat -> state.stats.merge(stat.stat(), stat.delta(),
                        (before, delta) -> Math.max(0, Math.min(100, before + delta)));
                case EventDsl.ModifierEffect modifier -> {
                    state.modifiers.add(new QuarterCalculator.Modifier(modifier.target(), modifier.mode(),
                            modifier.value()));
                    state.modifierWindows.add(new double[] {turn + 1, turn + 1 + modifier.quarters()});
                }
                case EventDsl.Flag flag -> {
                    if (flag.value()) {
                        state.flags.add(flag.flag());
                    } else {
                        state.flags.remove(flag.flag());
                    }
                }
                case EventDsl.ShopCount count -> state.shopCount = Math.max(1, Math.min(5,
                        state.shopCount + count.delta()));
                case EventDsl.RivalAdd rival -> state.virtualRivals += rival.count();
                case EventDsl.Text ignored -> {}
            }
        }
    }

    private static double mean(List<Outcome> outcomes, java.util.function.ToDoubleFunction<Outcome> value) {
        return outcomes.stream().mapToDouble(value).average().orElse(Double.NaN);
    }

    /** 이전 분기에도 전환점이 있었던 발생의 비율. 첫 발생은 분모에서 뺀다. */
    private static double consecutive(List<Outcome> outcomes) {
        int total = 0;
        int adjacent = 0;
        for (Outcome outcome : outcomes) {
            for (int index = 1; index < outcome.fires().size(); index++) {
                total++;
                if (outcome.fires().get(index).turn() == outcome.fires().get(index - 1).turn() + 1) {
                    adjacent++;
                }
            }
        }
        return total == 0 ? 0 : adjacent * 100d / total;
    }

    /** 같은 전환점이 한 게임에 두 번 이상 나온 게임 비율. */
    private static double repeated(List<Outcome> outcomes) {
        return outcomes.stream().filter(outcome -> outcome.fires().stream().map(Fire::code).distinct().count()
                < outcome.fires().size()).count() * 100d / outcomes.size();
    }

    private static double gap(List<Outcome> outcomes) {
        List<Integer> gaps = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            for (int index = 1; index < outcome.fires().size(); index++) {
                gaps.add(outcome.fires().get(index).turn() - outcome.fires().get(index - 1).turn());
            }
        }
        return gaps.stream().mapToInt(Integer::intValue).average().orElse(Double.NaN);
    }

    private static double successGap(List<Outcome> outcomes) {
        double expected = 0;
        int successes = 0;
        int total = 0;
        for (Outcome outcome : outcomes) {
            for (Fire fire : outcome.fires()) {
                expected += fire.ratePct() / 100d;
                successes += fire.success() ? 1 : 0;
                total++;
            }
        }
        return total == 0 ? 0 : (successes - expected) * 100 / total;
    }

    private static void compareGap(StringBuilder report, List<Outcome> before, List<Outcome> after) {
        List<Outcome> left = before.stream().filter(o -> o.turns() == TURNS).toList();
        List<Outcome> right = after.stream().filter(o -> o.turns() == TURNS).toList();
        report.append("## 전역 간격 비교\n\n|지표|조정 후 (V12)|전역 간격 1|\n|---|---:|---:|\n");
        row(report, "게임당 전환점 평균 (전체)", mean(before, o -> o.fires().size()), mean(after, o -> o.fires().size()), "%.2f");
        row(report, "게임당 전환점 평균 (12분기 완주 게임)", mean(left, o -> o.fires().size()), mean(right, o -> o.fires().size()), "%.2f");
        row(report, "완주 게임 중 전환점 3~5회 비율 (%)", share(left, 3, 5), share(right, 3, 5), "%.2f");
        row(report, "연속 분기 발생 비율 (%)", consecutive(before), consecutive(after), "%.2f");
        report.append('\n');
    }

    private static void compare(StringBuilder report, List<Outcome> before, List<Outcome> after) {
        List<Outcome> fullBefore = before.stream().filter(outcome -> outcome.turns() == TURNS).toList();
        List<Outcome> fullAfter = after.stream().filter(outcome -> outcome.turns() == TURNS).toList();
        report.append("## 요약\n\n|지표|조정 전|조정 후 (V12)|\n|---|---:|---:|\n");
        row(report, "게임 수", before.size(), after.size(), "%.0f");
        row(report, "게임당 전환점 평균 (전체)", mean(before, o -> o.fires().size()), mean(after, o -> o.fires().size()),
                "%.2f");
        row(report, "게임당 전환점 평균 (12분기 완주 게임)", mean(fullBefore, o -> o.fires().size()),
                mean(fullAfter, o -> o.fires().size()), "%.2f");
        row(report, "12분기 완주 게임 비율 (%)", fullBefore.size() * 100d / before.size(),
                fullAfter.size() * 100d / after.size(), "%.2f");
        row(report, "완주 게임 중 전환점 3~5회 비율 (%)", share(fullBefore, 3, 5), share(fullAfter, 3, 5), "%.2f");
        row(report, "전환점 사이 평균 간격 (분기)", gap(before), gap(after), "%.2f");
        row(report, "연속 분기 발생 비율 (%)", consecutive(before), consecutive(after), "%.2f");
        row(report, "같은 전환점 반복 게임 비율 (%)", repeated(before), repeated(after), "%.2f");
        row(report, "선택 결과 성공 비율 (%)", successRate(before), successRate(after), "%.2f");
        row(report, "실제 성공률 − 표시 성공률 평균 (%p)", successGap(before), successGap(after), "%+.2f");
        row(report, "게임당 순간 평균", mean(before, Outcome::moments), mean(after, Outcome::moments), "%.2f");
        row(report, "순간 성공 비율 (%)", momentRate(before), momentRate(after), "%.2f");
        row(report, "분기당 반응 평균", reactionMean(before), reactionMean(after), "%.2f");
        report.append('\n');
    }

    private static double share(List<Outcome> outcomes, int min, int max) {
        return outcomes.stream().filter(o -> o.fires().size() >= min && o.fires().size() <= max).count() * 100d
                / outcomes.size();
    }

    private static double successRate(List<Outcome> outcomes) {
        long total = outcomes.stream().mapToLong(o -> o.fires().size()).sum();
        long success = outcomes.stream().mapToLong(o -> o.fires().stream().filter(Fire::success).count()).sum();
        return total == 0 ? 0 : success * 100d / total;
    }

    private static double momentRate(List<Outcome> outcomes) {
        long total = outcomes.stream().mapToLong(Outcome::moments).sum();
        long success = outcomes.stream().mapToLong(Outcome::momentSuccesses).sum();
        return total == 0 ? 0 : success * 100d / total;
    }

    private static double reactionMean(List<Outcome> outcomes) {
        return outcomes.stream().flatMap(o -> o.reactions().stream()).mapToInt(Integer::intValue).average()
                .orElse(Double.NaN);
    }

    private static void row(StringBuilder report, String label, double before, double after, String format) {
        report.append('|').append(label).append('|').append(String.format(Locale.ROOT, format, before)).append('|')
                .append(String.format(Locale.ROOT, format, after)).append("|\n");
    }

    private static void distribution(StringBuilder report, List<Outcome> before, List<Outcome> after) {
        report.append("## 게임당 전환점 수 분포 (12분기 완주 게임, %)\n\n|발생 수|조정 전|조정 후 (V12)|\n|---:|---:|---:|\n");
        List<Outcome> fullBefore = before.stream().filter(outcome -> outcome.turns() == TURNS).toList();
        List<Outcome> fullAfter = after.stream().filter(outcome -> outcome.turns() == TURNS).toList();
        for (int count = 0; count <= TURNS - 1; count++) {
            int value = count;
            double left = fullBefore.stream().filter(o -> o.fires().size() == value).count() * 100d / fullBefore.size();
            double right = fullAfter.stream().filter(o -> o.fires().size() == value).count() * 100d / fullAfter.size();
            if (left > 0 || right > 0) {
                report.append(String.format(Locale.ROOT, "|%d|%.2f|%.2f|%n", count, left, right));
            }
        }
        report.append('\n');
    }

    private static void perPoint(StringBuilder report, Definitions before, List<Outcome> beforeOutcomes,
            List<Outcome> afterOutcomes, Definitions after) {
        report.append("## 전환점별 발생률\n\n")
                .append("발생/게임 = 전체 게임 대비 발생 횟수, 반복 = 한 게임에 두 번 이상 나온 게임 비율.\n\n")
                .append("|전환점|기본 확률 전→후|쿨다운 전→후|발생/게임 전|발생/게임 후|반복 전 (%)|반복 후 (%)|\n")
                .append("|---|---:|---:|---:|---:|---:|---:|\n");
        Map<String, JsonNode> tuned = new HashMap<>();
        for (var point : after.points()) {
            tuned.put(point.code(), point.data());
        }
        for (var point : before.points()) {
            String code = point.code();
            JsonNode next = tuned.get(code);
            report.append(String.format(Locale.ROOT, "|%s|%.2f → %.2f|%d → %d|%.3f|%.3f|%.2f|%.2f|%n", code,
                    point.data().get("baseProbability").doubleValue(), next.get("baseProbability").doubleValue(),
                    point.data().get("cooldownQuarters").intValue(), next.get("cooldownQuarters").intValue(),
                    fires(beforeOutcomes, code), fires(afterOutcomes, code), repeats(beforeOutcomes, code),
                    repeats(afterOutcomes, code)));
        }
        report.append('\n');
    }

    private static double fires(List<Outcome> outcomes, String code) {
        return outcomes.stream().mapToLong(o -> o.fires().stream().filter(f -> f.code().equals(code)).count()).sum()
                / (double) outcomes.size();
    }

    private static double repeats(List<Outcome> outcomes, String code) {
        return outcomes.stream().filter(o -> o.fires().stream().filter(f -> f.code().equals(code)).count() >= 2)
                .count() * 100d / outcomes.size();
    }

    /** 선택지별 표본 성공률과 표시 성공률(화면의 반올림 %)의 평균 비교. ±5%p 밖이면 표시한다. */
    private static void perOption(StringBuilder report, String title, List<Outcome> outcomes) {
        report.append("## 선택지별 성공률: ").append(title).append("\n\n")
                .append("|전환점#선택지|선택 수|표시 성공률 평균 (%)|실제 성공률 (%)|차이 (%p)|±5%p 이내|\n")
                .append("|---|---:|---:|---:|---:|---|\n");
        Map<String, List<Fire>> groups = new TreeMap<>();
        for (Outcome outcome : outcomes) {
            for (Fire fire : outcome.fires()) {
                groups.computeIfAbsent(fire.code() + "#" + fire.order(), key -> new ArrayList<>()).add(fire);
            }
        }
        groups.forEach((key, fires) -> {
            double shown = fires.stream().mapToInt(Fire::ratePct).average().orElse(0);
            double actual = fires.stream().filter(Fire::success).count() * 100d / fires.size();
            report.append(String.format(Locale.ROOT, "|%s|%d|%.2f|%.2f|%+.2f|%s|%n", key, fires.size(), shown, actual,
                    actual - shown, Math.abs(actual - shown) <= 5 ? "예" : fires.size() < 100 ? "표본 부족" : "아니오"));
        });
        report.append('\n');
    }

    /** 표식 사이만 바꾸고 사람이 쓴 해설은 보존한다. */
    private static void write(String tables) throws Exception {
        String block = BEGIN + "\n" + tables + END;
        String content;
        if (Files.exists(REPORT) && Files.readString(REPORT).contains(BEGIN)) {
            String old = Files.readString(REPORT);
            content = old.substring(0, old.indexOf(BEGIN)) + block + old.substring(old.indexOf(END) + END.length());
        } else {
            content = "# 이벤트 밸런스 시뮬레이션\n\n" + block + "\n";
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, content);
    }
}
