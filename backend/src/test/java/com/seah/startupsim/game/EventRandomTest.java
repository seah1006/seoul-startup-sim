package com.seah.startupsim.game;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.seah.startupsim.game.EventRandom.Purpose.TURNING_POINT_FIRE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 목적·코드별 분리와 정의 추가에 대한 판정 안정성을 검증한다. */
class EventRandomTest {
    @Test
    void knownFnvVectors() {
        assertEquals(0xCBF29CE484222325L, EventRandom.hash64(""));
        assertEquals(0xAF63DC4C8601EC8CL, EventRandom.hash64("a"));
        assertEquals(0xA430D84680AABD0BL, EventRandom.hash64("hello"));
    }

    @Test
    void sameInputReplaysAndCodeOrderDoesNotMatter() {
        Map<String, Double> original = draws(List.of("RENT_HIKE", "SECOND_BRANCH"));
        Map<String, Double> extended = draws(List.of("NEW_EVENT", "SECOND_BRANCH", "RENT_HIKE"));
        original.forEach((code, value) -> assertEquals(value, extended.get(code)));
        assertEquals(original, draws(List.of("RENT_HIKE", "SECOND_BRANCH")));
    }

    private Map<String, Double> draws(List<String> codes) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String code : codes) {
            result.put(code, EventRandom.stream(123, 4, TURNING_POINT_FIRE, code).nextDouble());
        }
        return result;
    }

    @Test
    void streamsAreSeparatedFromRevenueAndEachOther() {
        long revenueSeed = 123 ^ (4L * 0x9E3779B97F4A7C15L);
        var seeds = new java.util.HashSet<Long>();
        for (var purpose : EventRandom.Purpose.values()) {
            for (String code : List.of("", "RENT_HIKE", "SECOND_BRANCH#1", "한글")) {
                long seed = EventRandom.streamSeed(123, 4, purpose, code);
                assertNotEquals(revenueSeed, seed);
                assertTrue(seeds.add(seed));
            }
        }
        assertNotEquals(EventRandom.streamSeed(123, 4, TURNING_POINT_FIRE, "RENT_HIKE"),
                EventRandom.streamSeed(123, 5, TURNING_POINT_FIRE, "RENT_HIKE"));
    }

    @Test
    void tenThousandJudgmentsStayWithinThreePercentagePoints() {
        for (double probability : List.of(0.1, 0.35, 0.9)) {
            int fires = 0;
            for (int seed = 0; seed < 10000; seed++) {
                if (EventRandom.stream(seed, 4, TURNING_POINT_FIRE, "RENT_HIKE").nextDouble() < probability) {
                    fires++;
                }
            }
            assertEquals(probability, fires / 10000.0, 0.03);
        }
    }
}
