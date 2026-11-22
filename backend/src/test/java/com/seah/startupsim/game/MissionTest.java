package com.seah.startupsim.game;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class MissionTest {
    @Test void fiveCodesHaveStableOrderAndDefaults() {
        assertEquals(List.of("SURVIVE_3Y", "PROFIT_100M", "AREA_TOP3", "BLACK_4", "SECOND_SHOP"),
                Mission.definitions().stream().map(Mission.Definition::code).toList());
        assertEquals(Mission.SURVIVE_3Y, Mission.parse(null));
        assertThrows(GameException.class, () -> Mission.parse("OTHER"));
    }
    @Test void allFiveThresholdsAndMissingRank() {
        for (Mission mission : Mission.values()) {
            assertFalse(mission.achieved(null));
            assertTrue(mission.achieved(mission.target));
            if (mission == Mission.AREA_TOP3) {
                assertTrue(mission.achieved(1L)); assertFalse(mission.achieved(0L)); assertFalse(mission.achieved(mission.target + 1));
            } else {
                assertFalse(mission.achieved(mission.target - 1));
            }
        }
    }
    @Test void streakStopsOnZeroOrLoss() {
        assertEquals(4, MissionRepository.streak(List.of(1L, 2L, 3L, 4L, 0L, 10L)));
        assertEquals(0, MissionRepository.streak(List.of(0L, 10L)));
        assertEquals(1, MissionRepository.streak(List.of(10L, -1L, 10L)));
    }
}
