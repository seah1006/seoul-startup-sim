package com.seah.startupsim.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EffectValueFormatTest {
    @Test
    void percentKeepsOneDecimalAndDropsTrailingZero() {
        assertEquals("+25", EventEffectService.percent(25.0));
        assertEquals("+1.5", EventEffectService.percent(1.5));
        assertEquals("-0.8", EventEffectService.percent(-0.75));
        assertEquals("+0.3", EventEffectService.percent(0.25));
        assertEquals("-10", EventEffectService.percent(-10.0000001));
        assertEquals("+0", EventEffectService.percent(0.0));
    }
}
