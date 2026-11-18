package com.seah.startupsim.game;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class EventGlobalGapTest {
    private final EventEngine engine = new EventEngine(null, null, new EventDefinitionValidator(), null, null, null, null);
    private final EventDefinitionRepository.Definition point = new EventDefinitionRepository.Definition(1, 1,
            new ObjectMapper().readTree("""
            {"code":"NEW","conditionExpr":{},"baseProbability":1,"statBonus":[],"probMin":0,"probMax":1,
             "cooldownQuarters":0,"maxFires":10,"minTurn":0}
            """));

    private boolean fires(int since, double gap) {
        Map<String, Object> variables = new HashMap<>();
        if (since >= 0) variables.put("history.quartersSince.OTHER", since);
        return engine.judge(new GameContext(1, 1, 404, 5, "20232", null), variables,
                Map.of(), List.of(point), gap).get("NEW");
    }

    @Test void zeroPreservesLegacyEvenAfterAnotherPoint() { assertTrue(fires(1, 0)); }
    @Test void noHistoryCanFire() { assertTrue(fires(-1, 1)); }
    @Test void gapOneBlocksPreviousQuarterAcrossDefinitions() { assertFalse(fires(1, 1)); }
    @Test void gapOneAllowsTwoQuartersLater() { assertTrue(fires(2, 1)); }
    @Test void gapTwoBlocksTwoQuartersAndAllowsThree() {
        assertFalse(fires(2, 2));
        assertTrue(fires(3, 2));
    }
}
