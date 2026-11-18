package com.seah.startupsim.game;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventEffectSummaryTest {
    private final EventEffectService effects = new EventEffectService(null);

    @Test
    @DisplayName("선택 전 효과 요약의 현금은 화면 금액 규칙(만 원, 공백 포함)을 따르고 보정은 라벨을 쓴다")
    void cashUsesScreenMoneyFormat() {
        assertEquals("임대료 인상분 -300만 원, 임대료 +150만 원, 체력 -2", effects.summary(List.of(
                new EventDsl.Cash(-3000000, "임대료 인상분"),
                new EventDsl.ModifierEffect("rent", "ADD", 1500000, 4, "임대료 +150만 원"),
                new EventDsl.Flag("RENT_ACCEPTED", true),
                new EventDsl.Stat("stamina", -2))));
        assertEquals("지원금 +2,500원", effects.summary(List.of(new EventDsl.Cash(2500, "지원금"))));
        assertEquals("가맹비 -1,000만 원", effects.summary(List.of(new EventDsl.Cash(-10000000, "가맹비"))));
        assertEquals("변화 없음", effects.summary(List.of()));
    }

    @Test
    @DisplayName("선택 결과 효과 값(describe)도 선택 전과 같은 화면 금액 규칙을 쓴다")
    void describeUsesScreenMoneyFormat() {
        assertEquals("-200만 원", effects.describe(List.of(new EventDsl.Cash(-2000000, "포장재"))).getFirst().value());
        assertEquals("+150만 원", effects.describe(List.of(
                new EventDsl.ModifierEffect("rent", "ADD", 1500000, 4, "임대료"))).getFirst().value());
        assertEquals("+2,500원", effects.describe(List.of(new EventDsl.Cash(2500, "지원금"))).getFirst().value());
    }
}