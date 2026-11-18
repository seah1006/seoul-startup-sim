package com.seah.startupsim.game;

import com.seah.startupsim.game.GameTypes.Costs;
import com.seah.startupsim.game.GameTypes.Decision;
import com.seah.startupsim.game.GameTypes.StatChange;
import com.seah.startupsim.game.QuarterOutcome.AreaNews;
import com.seah.startupsim.game.QuarterOutcome.AreaRank;
import com.seah.startupsim.game.QuarterOutcome.Headline;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.springframework.stereotype.Component;

/** DB와 무관한 순수 계산기로 같은 입력의 분기 결산을 재현한다. */
@Component
public class QuarterCalculator {
    public record Input(long seed, int turn, String quarter, long cash, Long marketSales,
            long districtPerStore, int stores, int rivals, double areaM2, long openings, long closings,
            Map<String, Integer> stats, List<String> focus, Decision decision, Map<String, Double> settings,
            String districtCode, String serviceCode, List<Modifier> modifiers) {
        public Input {
            modifiers = List.copyOf(modifiers);
        }

        /** 업종 코드 없이 호출하면 업종별 원가율 없이 분류 원가율(cost.FOOD)만 쓴다. */
        public Input(long seed, int turn, String quarter, long cash, Long marketSales,
                long districtPerStore, int stores, int rivals, double areaM2, long openings, long closings,
                Map<String, Integer> stats, List<String> focus, Decision decision, Map<String, Double> settings,
                String districtCode) {
            this(seed, turn, quarter, cash, marketSales, districtPerStore, stores, rivals, areaM2,
                    openings, closings, stats, focus, decision, settings, districtCode, null);
        }

        /** 기존 시장 스냅샷 호출은 빈 보정으로 유지해 데이터 버전 고정 코드와 분리한다. */
        public Input(long seed, int turn, String quarter, long cash, Long marketSales,
                long districtPerStore, int stores, int rivals, double areaM2, long openings, long closings,
                Map<String, Integer> stats, List<String> focus, Decision decision, Map<String, Double> settings,
                String districtCode, String serviceCode) {
            this(seed, turn, quarter, cash, marketSales, districtPerStore, stores, rivals, areaM2,
                    openings, closings, stats, focus, decision, settings, districtCode, serviceCode, List.of());
        }

        public Input withModifiers(List<Modifier> active) {
            return new Input(seed, turn, quarter, cash, marketSales, districtPerStore, stores, rivals, areaM2,
                    openings, closings, stats, focus, decision, settings, districtCode, serviceCode, active);
        }
    }

    /** 계산기는 지속 기간·출처를 모르고 검증된 수치 보정만 받는다. */
    public record Modifier(String target, String mode, double value) {
        public Modifier {
            if (!List.of("revenue", "costRate", "rent", "visitors").contains(target)
                    || !List.of("ADD", "MULTIPLY").contains(mode) || !Double.isFinite(value)
                    || mode.equals("MULTIPLY") && (value < 0.1 || value > 3)) {
                throw new IllegalArgumentException("허용되지 않은 계산기 보정입니다.");
            }
        }
    }

    /** 턴별 시드만 사용하므로 재시도나 다른 게임 진행이 매출 난수에 영향을 주지 않는다. */
    public QuarterOutcome calculate(Input input) {
        Decision decision = input.decision();
        Map<String, Double> settings = input.settings();
        long market = input.marketSales() == null
                ? Math.max(0, input.districtPerStore()) * Math.max(0, input.stores())
                : Math.max(0, input.marketSales());
        double price = switch (decision.priceLevel()) {
            case LOW -> 0.8;
            case NORMAL -> 1;
            case HIGH -> 1.25;
        };
        long ticket = Math.round(settings.getOrDefault("ticket", 12000d) * price);
        double appeal = settings.getOrDefault("weight.cooking", .25) * input.stats().get("cooking") / 40d
                + settings.getOrDefault("weight.service", .2) * input.stats().get("service") / 40d
                + settings.getOrDefault("weight.marketing", .15)
                * (input.stats().get("marketing") / 40d + Math.log1p(decision.marketingBudget() / 1000000d))
                + settings.getOrDefault("weight.hours", .2) * decision.openHours() / 10d
                + settings.getOrDefault("weight.staff", .2) * (decision.staff() + 1) / 3d;
        double expectedNearby = Math.max(1,
                Math.max(0, input.stores()) * Math.min(1, Math.PI * 300 * 300 / Math.max(1, input.areaM2())));
        double density = 1 / (1 + Math.max(0, input.rivals() / expectedNearby - 1) * .15);
        double random = new SplittableRandom(input.seed() ^ ((long) input.turn() * 0x9E3779B97F4A7C15L))
                .nextDouble(.9, 1.1);
        long capacity = (long) ((decision.staff() + 1) * decision.openHours() * 90
                * settings.getOrDefault("capacityPerHour", 8d));
        long visitors = Math.min(capacity, Math.max(0, (long) (market / (double) (Math.max(0, input.stores()) + 1)
                * appeal * density * random / price / ticket)));
        if (hasModifier(input, "visitors")) {
            double baseVisitors = market / (double) (Math.max(0, input.stores()) + 1)
                    * appeal * density * random / price / ticket;
            visitors = Math.min(capacity, Math.max(0, (long) modify(baseVisitors, input, "visitors")));
        }
        long revenue = Math.multiplyExact(visitors, ticket);
        if (hasModifier(input, "revenue")) {
            revenue = Math.round(Math.max(0, modify(revenue, input, "revenue")));
        }
        long rent = settings.getOrDefault("rent." + input.districtCode(),
                settings.getOrDefault("rent.default", 9000000d)).longValue();
        double costRate = costRate(settings, input.serviceCode());
        long ingredients = Math.round(revenue * costRate
                * (1 - (input.stats().get("management") - 40) * .002));
        if (hasModifier(input, "costRate")) {
            double rate = costRate
                    * (1 - (input.stats().get("management") - 40) * .002);
            rate = Math.max(0.05, Math.min(0.95, modify(rate, input, "costRate")));
            ingredients = Math.round(revenue * rate);
        }
        if (hasModifier(input, "rent")) {
            rent = Math.round(Math.max(0, modify(rent, input, "rent")));
        }
        long labor = Math.round(decision.staff() * settings.getOrDefault("labor", 6000000d));
        long total = Math.addExact(Math.addExact(rent, ingredients), Math.addExact(labor, decision.marketingBudget()));
        long profit = revenue - total;
        List<StatChange> changes = new ArrayList<>();
        String activity = switch (decision.ownerActivity()) {
            case MENU_DEV -> "cooking";
            case REGULARS -> "service";
            case STUDY -> "management";
            case REST -> "stamina";
        };
        for (String stat : OwnerCandidates.STATS) {
            int before = input.stats().get(stat);
            int growth = (stat.equals(activity) ? 2 : 0) + (input.focus().contains(stat) ? 1 : 0)
                    + (stat.equals("management") ? 1 : 0);
            if (growth > 0) {
                changes.add(new StatChange(stat, before, Math.min(100, before + growth)));
            }
        }
        return new QuarterOutcome(input.turn(), input.quarter(), new Headline(profit, "이번 분기 순이익"),
                revenue, visitors, ticket, new Costs(rent, ingredients, labor, decision.marketingBudget(), total),
                profit, Math.addExact(input.cash(), profit), new AreaRank(Math.max(1, input.stores() + 1),
                Math.max(1, Math.min(input.stores() + 1,
                        (int) Math.ceil((input.stores() + 1) / Math.max(.1, appeal))))),
                changes, new AreaNews(input.closings(), input.openings(), "서울시 상권분석서비스 " + input.quarter()),
                null, List.of(), input.marketSales() == null
                        ? List.of("이 상권의 매출은 자치구 점포당 평균으로 추정한 값입니다") : List.of());
    }

    /**
     * 업종별 원가율 {@code cost.<serviceCode>}가 있으면 그 값을, 없으면 외식 분류 원가율 {@code cost.FOOD}를 쓴다.
     * 업종 키가 없으면 기존과 같은 값이므로 산술 경로와 결과가 바뀌지 않는다.
     */
    private static double costRate(Map<String, Double> settings, String serviceCode) {
        Double serviceRate = serviceCode == null ? null : settings.get("cost." + serviceCode);
        return serviceRate != null ? serviceRate : settings.getOrDefault("cost.FOOD", .35);
    }

    private boolean hasModifier(Input input, String target) {
        return input.modifiers().stream().anyMatch(modifier -> modifier.target().equals(target));
    }

    /** ADD를 모두 더한 뒤 MULTIPLY를 순서대로 적용한다. 대상 보정이 없으면 연산하지 않는다. */
    private double modify(double base, Input input, String target) {
        double result = base;
        for (Modifier modifier : input.modifiers()) {
            if (modifier.target().equals(target) && modifier.mode().equals("ADD")) {
                result += modifier.value();
            }
        }
        for (Modifier modifier : input.modifiers()) {
            if (modifier.target().equals(target) && modifier.mode().equals("MULTIPLY")) {
                result *= modifier.value();
            }
        }
        return result;
    }
}
