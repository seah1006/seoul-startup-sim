package com.seah.startupsim;

import com.seah.startupsim.game.GameTypes.Activity;
import com.seah.startupsim.game.GameTypes.Decision;
import com.seah.startupsim.game.GameTypes.Price;
import com.seah.startupsim.game.QuarterCalculator;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 밸런스 시뮬레이터.
 *
 * <p>메인 DB에는 읽기 전용 세션의 SELECT만 실행하고 계산은 {@link QuarterCalculator}로 메모리에서 수행한다.
 * 결과 표는 {@code BALANCE_REPORT.md}의 시뮬레이션 표식 사이만 교체하고, 사람이 쓴 해설은 보존한다.
 */
@Tag("simulation")
class BalanceSimulationTest {
    private static final Path REPORT = Path.of("../docs/balance/BALANCE_REPORT.md");
    private static final Path V10 = Path.of("src/main/resources/db/migration/V10__balance.sql");
    private static final String BEGIN = "<!-- simulation:begin -->";
    private static final String END = "<!-- simulation:end -->";
    private static final List<String> POLICIES = List.of("보수", "표준", "공격");
    private static final double[] SWEEP = {.15, .20, .25, .30, .35, .40, .45, .50, .55, .60};

    record Market(String area, String service, String quarter, long sales, int stores,
            String district, double size, String type) {}

    record Outcome(String service, String policy, String type, boolean survived, int turns, List<Long> profits) {}

    @Test
    void simulate() throws Exception {
        Properties env = new Properties();
        try (var input = Files.newInputStream(Path.of("../.env"))) {
            env.load(input);
        }
        Map<String, Double> baseline = new HashMap<>();
        Map<String, String> names = new TreeMap<>();
        Map<String, Market> markets = new HashMap<>();
        Map<String, List<String>> areas = new TreeMap<>();
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
                        baseline.put(rs.getString(1), Double.valueOf(rs.getString(2)));
                    }
                }
                try (ResultSet rs = statement.executeQuery("""
                        SELECT service_code, name FROM service_industries
                        WHERE service_code LIKE 'CS1%'
                        """)) {
                    while (rs.next()) {
                        names.put(rs.getString(1), rs.getString(2));
                    }
                }
                try (ResultSet rs = statement.executeQuery("""
                        SELECT s.area_code, s.service_code, s.quarter, s.sales_amount,
                               coalesce(t.store_count, 0), a.district_code, coalesce(a.area_m2, 1), a.area_type_name
                        FROM area_sales_q s
                        JOIN trade_areas a USING (area_code)
                        LEFT JOIN area_stores_q t USING (area_code, service_code, quarter)
                        WHERE s.service_code LIKE 'CS1%' AND s.sales_amount IS NOT NULL
                        ORDER BY s.service_code, s.area_code, s.quarter
                        """)) {
                    while (rs.next()) {
                        Market market = new Market(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                                rs.getInt(5), rs.getString(6), rs.getDouble(7), rs.getString(8));
                        markets.put(key(market.service(), market.area(), market.quarter()), market);
                        List<String> list = areas.computeIfAbsent(market.service(), service -> new ArrayList<>());
                        if (!list.contains(market.area())) {
                            list.add(market.area());
                        }
                    }
                }
            }
        }
        sample(areas, markets);
        Map<String, Double> rentOnly = rentOnly(baseline);
        Map<String, Double> serviceCost = new HashMap<>(rentOnly);
        serviceCost.putAll(v10CostRates());
        List<Outcome> before = run(baseline, areas, markets);
        List<Outcome> afterRent = run(rentOnly, areas, markets);
        List<Outcome> afterCost = run(serviceCost, areas, markets);

        StringBuilder tables = new StringBuilder();
        tables.append("측정: ").append(Instant.now()).append("\n\n");
        tables.append("고정 시드 404, 외식 10업종 × 업종별 무작위 200상권 × 3정책 × 2021~2023의 11개 시작 분기")
                .append("(2023Q4는 2026Q3 미공개로 제외), 각 12턴. 연속 실측 매출 보유 상권만 사용. ")
                .append("자본 음수 2분기 연속 시 파산. 사장 능력치는 모두 40, 집중은 요리·서비스. ")
                .append("실제 점포 반경 조회 대신 상권 점포수 기반 기대 밀도(밀집 보정 1) 사용. ")
                .append("이벤트·전환점 제외. 따라서 실제 게임 생존율이 아닌 통제 실험이다.\n\n");
        tables.append("보수: NORMAL/10시간/직원1/마케팅50만원, 표준: NORMAL/10시간/직원2/100만원, ")
                .append("공격: HIGH/14시간/직원3/300만원.\n\n");
        summary(tables, names, areas, markets, serviceCost, before, afterRent, afterCost);
        sweep(tables, names, areas, markets, rentOnly);
        append(tables, "조정 전 (V9까지의 game_settings)", before);
        append(tables, "1차 조정: 임대료만 (rent.* 15% 인하)", afterRent);
        append(tables, "2차 조정: 임대료 + 업종별 원가율 (V10 최종)", afterCost);
        write(tables.toString());
        System.out.println(tables);
    }

    private static String key(String service, String area, String quarter) {
        return service + ":" + area + ":" + quarter;
    }

    private static String quarter(int index) {
        return String.valueOf(index / 4) + (index % 4 + 1);
    }

    /** 공개 자료로 완주 가능한 11개 시작 분기 모두 실측 매출이 있는 상권 중 업종별 200곳을 고정 시드로 고른다. */
    private static void sample(Map<String, List<String>> areas, Map<String, Market> markets) {
        for (var entry : areas.entrySet()) {
            entry.getValue().removeIf(area -> {
                for (int index = 2021 * 4; index < 2023 * 4 + 2 + 12; index++) {
                    if (!markets.containsKey(key(entry.getKey(), area, quarter(index)))) {
                        return true;
                    }
                }
                return false;
            });
            Collections.shuffle(entry.getValue(), new Random(404));
            if (entry.getValue().size() < 200) {
                throw new IllegalStateException("연속 자료 상권 200개 미만: " + entry.getKey());
            }
            entry.setValue(new ArrayList<>(entry.getValue().subList(0, 200)));
        }
    }

    /** V10 1차 조정과 같은 산술: 모든 rent.* 값을 15% 내리고 원 단위 반올림. */
    private static Map<String, Double> rentOnly(Map<String, Double> baseline) {
        Map<String, Double> adjusted = new HashMap<>(baseline);
        adjusted.replaceAll((key, value) -> key.startsWith("rent.") ? Math.round(value * 0.85) * 1d : value);
        return adjusted;
    }

    /** 시뮬레이터와 마이그레이션 값이 어긋나지 않도록 V10 SQL에서 업종별 원가율을 그대로 읽는다. */
    private static Map<String, Double> v10CostRates() throws Exception {
        Matcher matcher = Pattern.compile("\\('(cost\\.CS1\\d{5})',\\s*'([0-9.]+)'\\)")
                .matcher(Files.readString(V10));
        Map<String, Double> rates = new TreeMap<>();
        while (matcher.find()) {
            rates.put(matcher.group(1), Double.valueOf(matcher.group(2)));
        }
        return rates;
    }

    private static List<Outcome> run(Map<String, Double> settings, Map<String, List<String>> areas,
            Map<String, Market> markets) {
        List<Outcome> outcomes = new ArrayList<>();
        QuarterCalculator calculator = new QuarterCalculator();
        for (String service : areas.keySet()) {
            for (String area : areas.get(service)) {
                for (int policy = 0; policy < 3; policy++) {
                    for (int start = 2021 * 4; start <= 2023 * 4 + 2; start++) {
                        outcomes.add(play(calculator, settings, markets, service, area, policy, start));
                    }
                }
            }
        }
        return outcomes;
    }

    private static Outcome play(QuarterCalculator calculator, Map<String, Double> settings,
            Map<String, Market> markets, String service, String area, int policy, int start) {
        Decision decision = new Decision(policy == 2 ? Price.HIGH : Price.NORMAL, policy == 2 ? 14 : 10,
                policy + 1, new long[] {500000, 1000000, 3000000}[policy], Activity.STUDY);
        Map<String, Integer> stats = new HashMap<>();
        for (String stat : List.of("cooking", "service", "management", "marketing", "stamina", "network")) {
            stats.put(stat, 40);
        }
        long cash = settings.get("initialCapital").longValue();
        int negative = 0;
        List<Long> profits = new ArrayList<>();
        Market first = markets.get(key(service, area, quarter(start)));
        for (int turn = 0; turn < 12; turn++) {
            Market market = markets.get(key(service, area, quarter(start + turn)));
            var result = calculator.calculate(new QuarterCalculator.Input(404L ^ area.hashCode() ^ start, turn,
                    market.quarter(), cash, market.sales(), 0, market.stores(), 0, market.size(), 0, 0, stats,
                    List.of("cooking", "service"), decision, settings, market.district(), service));
            profits.add(result.profit());
            cash = result.cashAfter();
            for (var change : result.statChanges()) {
                stats.put(change.stat(), change.after());
            }
            negative = cash < 0 ? negative + 1 : 0;
            if (negative >= 2) {
                break;
            }
        }
        return new Outcome(service, POLICIES.get(policy), first.type(), negative < 2, profits.size(), profits);
    }

    private static double survival(List<Outcome> outcomes, String service, String policy) {
        List<Outcome> group = outcomes.stream()
                .filter(outcome -> outcome.service().equals(service) && outcome.policy().equals(policy))
                .toList();
        return group.stream().filter(Outcome::survived).count() * 100d / group.size();
    }

    private static double overall(List<Outcome> outcomes) {
        List<Outcome> group = outcomes.stream().filter(outcome -> outcome.policy().equals("표준")).toList();
        return group.stream().filter(Outcome::survived).count() * 100d / group.size();
    }

    /** 표본 상권·기간의 분기 매출 ÷ (점포 수 + 1) 중앙값. 업종 간 실제 매출 차이의 기준으로 쓴다. */
    private static long medianSalesPerStore(String service, Map<String, List<String>> areas,
            Map<String, Market> markets) {
        List<Long> values = new ArrayList<>();
        for (String area : areas.get(service)) {
            for (int index = 2021 * 4; index < 2023 * 4 + 2 + 12; index++) {
                Market market = markets.get(key(service, area, quarter(index)));
                values.add(market.sales() / (Math.max(0, market.stores()) + 1));
            }
        }
        values.sort(Comparator.naturalOrder());
        return values.get(values.size() / 2);
    }

    private static void summary(StringBuilder report, Map<String, String> names, Map<String, List<String>> areas,
            Map<String, Market> markets, Map<String, Double> serviceCost, List<Outcome> before,
            List<Outcome> afterRent, List<Outcome> afterCost) {
        report.append("## 요약: 표준 정책 업종별 12분기 생존율\n\n")
                .append("점포당 매출 = 표본 상권·기간의 분기 매출 ÷ (점포 수 + 1) 중앙값(계산기의 시장 분할과 같은 분모). ")
                .append("점포당 매출 내림차순 정렬.\n\n")
                .append("|업종|점포당 분기 매출 중앙값 (원)|원가율|조정 전|임대료만|임대료+업종별 원가율|\n")
                .append("|---|---:|---:|---:|---:|---:|\n");
        Map<String, Long> sales = new LinkedHashMap<>();
        for (String service : areas.keySet()) {
            sales.put(service, medianSalesPerStore(service, areas, markets));
        }
        sales.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).forEach(entry -> {
            String service = entry.getKey();
            double rate = serviceCost.getOrDefault("cost." + service, serviceCost.get("cost.FOOD"));
            report.append(String.format(Locale.ROOT, "|%s %s|%d|%.2f|%.2f%%|%.2f%%|%.2f%%|%n", service,
                    names.getOrDefault(service, ""), entry.getValue(), rate, survival(before, service, "표준"),
                    survival(afterRent, service, "표준"), survival(afterCost, service, "표준")));
        });
        report.append(String.format(Locale.ROOT, "|전체|-|-|%.2f%%|%.2f%%|%.2f%%|%n%n",
                overall(before), overall(afterRent), overall(afterCost)));
    }

    /** 임대료 조정 후 한 업종의 원가율만 바꿨을 때 표준 정책 생존율. 원가율 선택의 근거 자료다. */
    private static void sweep(StringBuilder report, Map<String, String> names, Map<String, List<String>> areas,
            Map<String, Market> markets, Map<String, Double> rentOnly) {
        report.append("## 원가율 민감도 (임대료 조정 후, 표준 정책 생존율)\n\n|업종|");
        for (double rate : SWEEP) {
            report.append(String.format(Locale.ROOT, "%.2f|", rate));
        }
        report.append("\n|---|").append("---:|".repeat(SWEEP.length)).append('\n');
        QuarterCalculator calculator = new QuarterCalculator();
        for (String service : areas.keySet()) {
            report.append('|').append(service).append(' ').append(names.getOrDefault(service, "")).append('|');
            for (double rate : SWEEP) {
                Map<String, Double> settings = new HashMap<>(rentOnly);
                settings.put("cost." + service, rate);
                int survived = 0;
                int total = 0;
                for (String area : areas.get(service)) {
                    for (int start = 2021 * 4; start <= 2023 * 4 + 2; start++) {
                        total++;
                        if (play(calculator, settings, markets, service, area, 1, start).survived()) {
                            survived++;
                        }
                    }
                }
                report.append(String.format(Locale.ROOT, "%.2f%%|", survived * 100d / total));
            }
            report.append('\n');
        }
        report.append('\n');
    }

    private static void append(StringBuilder report, String title, List<Outcome> outcomes) {
        report.append("## ").append(title).append("\n\n")
                .append("|업종/유형|정책|생존율|순이익 p10/p50/p90 (원)|파산 평균 턴|\n")
                .append("|---|---|---:|---:|---:|\n");
        Map<String, List<Outcome>> groups = new TreeMap<>();
        for (Outcome outcome : outcomes) {
            groups.computeIfAbsent(outcome.service() + "|" + outcome.policy(), key -> new ArrayList<>()).add(outcome);
            groups.computeIfAbsent("유형:" + outcome.type() + "|" + outcome.policy(), key -> new ArrayList<>())
                    .add(outcome);
            groups.computeIfAbsent("전체|" + outcome.policy(), key -> new ArrayList<>()).add(outcome);
        }
        groups.forEach((key, values) -> {
            List<Long> profits = values.stream()
                    .flatMap(value -> value.profits().stream())
                    .sorted(Comparator.naturalOrder())
                    .toList();
            double survival = values.stream().filter(Outcome::survived).count() * 100d / values.size();
            double bankruptcy = values.stream()
                    .filter(value -> !value.survived())
                    .mapToInt(Outcome::turns)
                    .average()
                    .orElse(Double.NaN);
            int last = profits.size() - 1;
            report.append(String.format(Locale.ROOT, "|%s|%.2f%%|%d / %d / %d|%.2f|%n", key, survival,
                    profits.get(last / 10), profits.get(last / 2), profits.get(last * 9 / 10), bankruptcy));
        });
        report.append('\n');
    }

    /** 표식이 있으면 그 사이만 바꾸고, 없으면 표식으로 감싼 새 보고서를 만든다. */
    private static void write(String tables) throws Exception {
        String block = BEGIN + "\n" + tables + END;
        String content;
        if (Files.exists(REPORT) && Files.readString(REPORT).contains(BEGIN)) {
            String old = Files.readString(REPORT);
            content = old.substring(0, old.indexOf(BEGIN)) + block + old.substring(old.indexOf(END) + END.length());
        } else {
            content = "# 밸런스 시뮬레이션\n\n" + block + "\n";
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, content);
    }
}
