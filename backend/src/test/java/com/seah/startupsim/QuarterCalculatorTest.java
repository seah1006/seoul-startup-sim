package com.seah.startupsim;

import com.seah.startupsim.game.GameTypes;
import com.seah.startupsim.game.OwnerCandidates;
import com.seah.startupsim.game.QuarterCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class QuarterCalculatorTest {
    final QuarterCalculator calculator=new QuarterCalculator();
    QuarterCalculator.Input input(Long sales,int stores) {
        return new QuarterCalculator.Input(123,0,"20231",50000000,sales,100000000,stores,2,1000000,1,2,
                new OwnerCandidates().generate(GameTypes.Background.CULINARY,123).getFirst().stats(),List.of("cooking"),
                new GameTypes.Decision(GameTypes.Price.NORMAL,10,0,0,GameTypes.Activity.MENU_DEV),Map.of(),"11440");
    }
    @Test @DisplayName("같은 입력은 같은 결과를 내며 모든 후보의 합계는 240이다")
    void deterministic() {
        assertEquals(calculator.calculate(input(1000000000L,10)),calculator.calculate(input(1000000000L,10)));
        for(var b:GameTypes.Background.values()) for(var c:new OwnerCandidates().generate(b,123)) assertEquals(240,c.stats().values().stream().mapToInt(Integer::intValue).sum());
    }
    @Test @DisplayName("매출 결측은 추정 표시하고 점포가 0이어도 나누기 오류가 없다")
    void missing() {
        assertFalse(calculator.calculate(input(null,0)).dataNotes().isEmpty());
        assertEquals(0,calculator.calculate(input(null,0)).revenue());
    }
    @Test @DisplayName("큰 시장에서도 직원과 영업시간의 처리량 상한을 넘지 않는다")
    void capacity() {
        assertEquals(7200,calculator.calculate(input(Long.MAX_VALUE,0)).visitors());
    }
}
