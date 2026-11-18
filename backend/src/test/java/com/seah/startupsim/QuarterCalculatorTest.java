package com.seah.startupsim;

import com.seah.startupsim.game.GameTypes;
import com.seah.startupsim.game.OwnerCandidates;
import com.seah.startupsim.game.QuarterCalculator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 경계 입력에서 산술 경로와 처리량 제한이 바뀌지 않는지 검증한다. */
class QuarterCalculatorTest {
    final QuarterCalculator calculator = new QuarterCalculator();
    QuarterCalculator.Input input(Long sales, int stores) {
        return new QuarterCalculator.Input(123, 0, "20231", 50000000, sales, 100000000, stores, 2, 1000000, 1, 2,
                new OwnerCandidates().generate(GameTypes.Background.CULINARY, 123).getFirst().stats(), List.of("cooking"),
                new GameTypes.Decision(GameTypes.Price.NORMAL, 10, 0, 0, GameTypes.Activity.MENU_DEV), Map.of(), "11440");
    }

    @Test
    @DisplayName("같은 입력은 같은 결과를 내며 모든 후보의 합계는 240이다")
    void deterministic() {
        assertEquals(calculator.calculate(input(1000000000L, 10)), calculator.calculate(input(1000000000L, 10)));
        for (var background : GameTypes.Background.values()) {
            for (var candidate : new OwnerCandidates().generate(background, 123)) {
                assertEquals(240, candidate.stats().values().stream().mapToInt(Integer::intValue).sum());
            }
        }
    }

    @Test
    @DisplayName("매출 결측은 추정 표시하고 점포가 0이어도 나누기 오류가 없다")
    void missing() {
        assertFalse(calculator.calculate(input(null, 0)).dataNotes().isEmpty());
        assertEquals(0, calculator.calculate(input(null, 0)).revenue());
    }

    @Test
    @DisplayName("큰 시장에서도 직원과 영업시간의 처리량 상한을 넘지 않는다")
    void capacity() {
        assertEquals(7200, calculator.calculate(input(Long.MAX_VALUE, 0)).visitors());
    }

    QuarterCalculator.Input withDecision(GameTypes.Price price, int hours, int staff) {
        var base = input(Long.MAX_VALUE, 0);
        return new QuarterCalculator.Input(base.seed(), base.turn(), base.quarter(), base.cash(),
                base.marketSales(), base.districtPerStore(), base.stores(), base.rivals(), base.areaM2(),
                base.openings(), base.closings(), base.stats(), base.focus(),
                new GameTypes.Decision(price, hours, staff, 0, GameTypes.Activity.REST),
                base.settings(), base.districtCode());
    }

    @Test
    @DisplayName("가격 수준별 객단가가 기존 배율을 따른다")
    void priceLevels() {
        assertEquals(9600, calculator.calculate(withDecision(GameTypes.Price.LOW, 6, 0)).avgTicket());
        assertEquals(12000, calculator.calculate(withDecision(GameTypes.Price.NORMAL, 6, 0)).avgTicket());
        assertEquals(15000, calculator.calculate(withDecision(GameTypes.Price.HIGH, 6, 0)).avgTicket());
    }

    @Test
    @DisplayName("최소·최대 직원과 영업시간에서도 처리량 상한이 정확하다")
    void capacityBoundaries() {
        assertEquals(4320, calculator.calculate(withDecision(GameTypes.Price.NORMAL, 6, 0)).visitors());
        assertEquals(126720, calculator.calculate(withDecision(GameTypes.Price.NORMAL, 16, 10)).visitors());
    }

    QuarterCalculator.Input withService(Map<String, Double> settings, String serviceCode) {
        var base = input(1000000000L, 10);
        return new QuarterCalculator.Input(base.seed(), base.turn(), base.quarter(), base.cash(),
                base.marketSales(), base.districtPerStore(), base.stores(), base.rivals(), base.areaM2(),
                base.openings(), base.closings(), base.stats(), base.focus(), base.decision(),
                settings, base.districtCode(), serviceCode);
    }

    long expectedIngredients(QuarterCalculator.Input input, long revenue, double rate) {
        return Math.round(revenue * rate * (1 - (input.stats().get("management") - 40) * .002));
    }

    @Test
    @DisplayName("업종별 원가율 키가 있으면 분류 원가율 대신 그 값을 쓴다")
    void serviceCostRate() {
        var settings = Map.of("cost.FOOD", .35, "cost.CS100010", .45);
        var input = withService(settings, "CS100010");
        var result = calculator.calculate(input);
        assertEquals(expectedIngredients(input, result.revenue(), .45), result.costs().ingredients());
        var other = withService(settings, "CS100001");
        var otherResult = calculator.calculate(other);
        assertEquals(expectedIngredients(other, otherResult.revenue(), .35), otherResult.costs().ingredients());
    }

    @Test
    @DisplayName("업종별 키가 없으면 업종 코드가 있어도 기존 분류 원가율 결과와 같다")
    void classCostRateFallback() {
        var settings = Map.of("cost.FOOD", .3);
        var legacy = input(1000000000L, 10);
        var legacyWithSettings = new QuarterCalculator.Input(legacy.seed(), legacy.turn(), legacy.quarter(),
                legacy.cash(), legacy.marketSales(), legacy.districtPerStore(), legacy.stores(), legacy.rivals(),
                legacy.areaM2(), legacy.openings(), legacy.closings(), legacy.stats(), legacy.focus(),
                legacy.decision(), settings, legacy.districtCode());
        assertEquals(calculator.calculate(legacyWithSettings), calculator.calculate(withService(settings, "CS100010")));
        assertEquals(calculator.calculate(legacy), calculator.calculate(withService(Map.of(), "CS100010")));
    }

    @Test
    @DisplayName("원가율 보정은 업종별 원가율을 기준으로 적용한다")
    void serviceCostRateWithModifier() {
        var input = withService(Map.of("cost.CS100010", .4), "CS100010")
                .withModifiers(List.of(new QuarterCalculator.Modifier("costRate", "MULTIPLY", 1.5)));
        var result = calculator.calculate(input);
        double rate = .4 * (1 - (input.stats().get("management") - 40) * .002) * 1.5;
        assertEquals(Math.round(result.revenue() * rate), result.costs().ingredients());
    }

    @Test
    @DisplayName("자치구 평균 추정과 같은 규모의 실제 매출은 산술 결과가 같다")
    void estimatedSalesPath() {
        var estimated = calculator.calculate(input(null, 10));
        var measured = calculator.calculate(input(1000000000L, 10));
        assertEquals(measured.revenue(), estimated.revenue());
        assertEquals(measured.profit(), estimated.profit());
        assertEquals(1, estimated.dataNotes().size());
        assertEquals(0, measured.dataNotes().size());
    }
}
