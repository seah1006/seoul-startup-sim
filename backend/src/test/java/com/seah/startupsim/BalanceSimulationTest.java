package com.seah.startupsim;

import com.seah.startupsim.game.GameTypes.Activity;
import com.seah.startupsim.game.GameTypes.Decision;
import com.seah.startupsim.game.GameTypes.Price;
import com.seah.startupsim.game.QuarterCalculator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 메인 DB에는 읽기 전용 세션의 SELECT만 실행하고 계산은 메모리에서 수행한다. */
@Tag("simulation")
class BalanceSimulationTest {
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
        Map<String, Market> markets = new HashMap<>();
        Map<String, List<String>> areas = new HashMap<>();
        try (var connection = DriverManager.getConnection(System.getProperty("simulation.url"),
                env.getProperty("DB_USERNAME"), env.getProperty("DB_PASSWORD"))) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.execute("SET default_transaction_read_only = on");
                try (var rs = statement.executeQuery("SHOW default_transaction_read_only")) {
                    rs.next();
                    assertEquals("on", rs.getString(1));
                }
                try (var rs = statement.executeQuery("SELECT key,value::text FROM game_settings")) {
                    while (rs.next()) baseline.put(rs.getString(1), Double.valueOf(rs.getString(2)));
                }
                try (var rs = statement.executeQuery("""
                        SELECT s.area_code,s.service_code,s.quarter,s.sales_amount,
                               coalesce(t.store_count,0),a.district_code,coalesce(a.area_m2,1),a.area_type_name
                        FROM area_sales_q s JOIN trade_areas a USING(area_code)
                        LEFT JOIN area_stores_q t USING(area_code,service_code,quarter)
                        WHERE s.service_code LIKE 'CS1%' AND s.sales_amount IS NOT NULL
                        ORDER BY s.service_code,s.area_code,s.quarter
                        """)) {
                    while (rs.next()) {
                        Market m = new Market(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),
                                rs.getInt(5),rs.getString(6),rs.getDouble(7),rs.getString(8));
                        markets.put(m.service()+":"+m.area()+":"+m.quarter(),m);
                        List<String> list = areas.computeIfAbsent(m.service(), key -> new ArrayList<>());
                        if (!list.contains(m.area())) list.add(m.area());
                    }
                }
            }
        }
        // 공개 자료로 완주 가능한 11개 시작 분기 모두 실측 매출이 있는 상권만 사용한다.
        for (var entry : areas.entrySet()) {
            entry.getValue().removeIf(area -> {
                for (int q = 2021*4; q < 2023*4+2+12; q++) {
                    if (!markets.containsKey(entry.getKey()+":"+area+":"+quarter(q))) return true;
                }
                return false;
            });
            java.util.Collections.shuffle(entry.getValue(), new java.util.Random(404));
            if (entry.getValue().size() < 200) throw new IllegalStateException("연속 자료 상권 200개 미만: "+entry.getKey()+"="+entry.getValue().size());
            entry.setValue(new ArrayList<>(entry.getValue().subList(0,200)));
        }
        StringBuilder report = new StringBuilder("# 밸런스 시뮬레이션\n\n측정: "+java.time.Instant.now()+"\n\n");
        report.append("고정 시드 404, 외식 10업종 × 업종별 무작위 200상권 × 3정책 × 2021~2023의 11개 시작 분기(2023Q4는 2026Q3 미공개로 제외), 각 12턴. 연속 실측 매출 보유 상권만 사용. 자본 음수 2분기 연속 시 파산. 사장 능력치는 모두 40, 집중은 요리·서비스. 실제 점포 반경 조회 대신 상권 점포수 기반 기대 밀도(밀집 보정 1) 사용. 이벤트·전환점 제외. 따라서 실제 게임 생존율이 아닌 통제 실험이다.\n\n보수: NORMAL/10시간/직원1/마케팅50만원, 표준: NORMAL/10시간/직원2/100만원, 공격: HIGH/14시간/직원3/300만원.\n\n");
        List<Outcome> before = run(baseline, areas, markets);
        append(report,"조정 전",before);
        Map<String,Double> adjusted = new HashMap<>(baseline);
        adjusted.replaceAll((key,value) -> key.startsWith("rent.") ? Math.round(value*0.85)*1d : value);
        append(report,"조정 후 (분기 임대료 15% 인하)",run(adjusted,areas,markets));
        report.append("\n## 조정 판단\nV10은 모든 rent.* 값을 15% 낮춘다. 원래 임대료는 실측 시세가 아닌 관리자 임의값이며, 자치구 간 상대 순서는 유지한다. 고정비를 소폭 완화해 커피 등 저매출 업종의 손실을 줄이며 원가율·인건비·매력도·시작 자본은 유지한다. 실제 임대시세 인하 주장이나 외부 원가 통계 추정이 아니다. 전체 표준은 목표 40~70% 범위지만 커피는 35.27%, 일식은 76.68%로 개별 업종 목표는 미달한다. 업종 간 0%/100%는 없다. 원가율 업종별 값은 현재 계산기가 cost.FOOD 하나만 읽으므로 업종별 튜닝에는 계산기 변경 제안이 필요하다. 이번에는 공식·game 패키지를 수정하지 않았다. area 패키지는 API/수집 책임이 혼재하고 동시 game 리팩터링 import 충돌을 피하기 위해 이동하지 않았다.\n\n## DB 안전성\n접속 직후 JDBC readOnly 및 SET default_transaction_read_only=on 적용, SHOW로 on 확인. 이후 SELECT 두 건만 실행. 메인 DB DDL/DML 및 Flyway 없음.\n");
        Path target = Path.of("../docs/balance/BALANCE_REPORT.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target,report);
        System.out.println(report);
    }

    private static String quarter(int q) {
        return String.valueOf(q/4)+(q%4+1);
    }

    private static List<Outcome> run(Map<String,Double> settings, Map<String,List<String>> areas, Map<String,Market> markets) {
        List<Outcome> outcomes = new ArrayList<>();
        QuarterCalculator calculator = new QuarterCalculator();
        for (String service : areas.keySet().stream().sorted().toList()) {
            for (String area : areas.get(service)) {
                for (int policy = 0; policy < 3; policy++) {
                    for (int start = 2021*4; start <= 2023*4+2; start++) {
                        Decision decision = new Decision(policy == 2 ? Price.HIGH : Price.NORMAL,
                                policy == 2 ? 14 : 10,policy+1, new long[]{500000,1000000,3000000}[policy],Activity.STUDY);
                        Map<String,Integer> stats = new HashMap<>();
                        for (String stat : List.of("cooking","service","management","marketing","stamina","network")) stats.put(stat,40);
                        long cash = settings.get("initialCapital").longValue();
                        int negative = 0;
                        List<Long> profits = new ArrayList<>();
                        Market first = markets.get(service+":"+area+":"+quarter(start));
                        for (int turn = 0; turn < 12; turn++) {
                            Market m = markets.get(service+":"+area+":"+quarter(start+turn));
                            var result = calculator.calculate(new QuarterCalculator.Input(404L ^ area.hashCode() ^ start,
                                    turn, m.quarter(),cash,m.sales(),0,m.stores(),0,m.size(),0,0,
                                    stats,List.of("cooking","service"),decision,settings,m.district()));
                            profits.add(result.profit());
                            cash = result.cashAfter();
                            for (var change : result.statChanges()) stats.put(change.stat(),change.after());
                            negative = cash < 0 ? negative+1 : 0;
                            if (negative >= 2) break;
                        }
                        outcomes.add(new Outcome(service,List.of("보수","표준","공격").get(policy),first.type(),negative<2,profits.size(),profits));
                    }
                }
            }
        }
        return outcomes;
    }

    private static void append(StringBuilder report,String title,List<Outcome> outcomes) {
        report.append("## ").append(title).append("\n\n|업종/유형|정책|생존율|순이익 p10/p50/p90 (원)|파산 평균 턴|\n|---|---|---:|---:|---:|\n");
        Map<String,List<Outcome>> groups = new java.util.TreeMap<>();
        for (Outcome outcome : outcomes) {
            groups.computeIfAbsent(outcome.service()+"|"+outcome.policy(),k->new ArrayList<>()).add(outcome);
            groups.computeIfAbsent("유형:"+outcome.type()+"|"+outcome.policy(),k->new ArrayList<>()).add(outcome);
            groups.computeIfAbsent("전체|"+outcome.policy(),k->new ArrayList<>()).add(outcome);
        }
        groups.forEach((key,values) -> {
            List<Long> profits = values.stream().flatMap(v->v.profits().stream()).sorted(Comparator.naturalOrder()).toList();
            double survival = values.stream().filter(Outcome::survived).count()*100d/values.size();
            double bankruptcy = values.stream().filter(v->!v.survived()).mapToInt(Outcome::turns).average().orElse(Double.NaN);
            report.append(String.format(java.util.Locale.ROOT,"|%s|%.2f%%|%d / %d / %d|%.2f|%n",key,survival,
                    profits.get((profits.size()-1)/10),profits.get((profits.size()-1)/2),profits.get((profits.size()-1)*9/10),bankruptcy));
        });
        report.append('\n');
    }
}
