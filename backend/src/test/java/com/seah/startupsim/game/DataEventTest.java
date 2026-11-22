package com.seah.startupsim.game;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DataEventTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EventDefinitionValidator validator = new EventDefinitionValidator();
    @Test void percentageUsesPreviousQuarterAndRejectsMissingOrZeroBaseline() {
        Map<String, Object> values = new HashMap<>();
        EventContextFactory.change(values, "area.salesChangePct", 88, 100);
        assertEquals(-12, ((Number) values.get("area.salesChangePct")).doubleValue(), 0.0001);
        for (Number previous : new Number[]{null, 0, -1}) {
            EventContextFactory.change(values, "missing", 50, previous);
            assertFalse(values.containsKey("missing"));
        }
    }
    @Test void dataVariablesAreWhitelistedAndMissingValuesAreFalse() {
        for (String variable : new String[]{"salesChangePct", "storeChange", "openings", "closings",
                "closingRatePct", "openingRatePct", "floatingPopChangePct", "franchiseChange"}) {
            var condition = validator.condition(json.readTree("""
                    {"var":"area.%s","op":">","value":0}
                    """.formatted(variable)), EventDefinitionValidator.Scope.TURNING_POINT);
            assertFalse(condition.matches(Map.of()));
            assertTrue(condition.matches(Map.of("area." + variable, 10)));
        }
        assertThrows(GameException.class, () -> validator.condition(json.readTree("""
                {"var":"area.notAllowed","op":">","value":0}
                """), EventDefinitionValidator.Scope.TURNING_POINT));
    }
    @Test void signedDataTextAndParticleRenderTogether() {
        Shop shop = new Shop("A", "가상 상권", "D", "CS100010", "커피", "작은 가게", 0, 0, 0, 1);
        assertEquals("작은 가게가 2022년 3분기 -12% / +2곳", EventText.render(
                "{shopName:이/가} {area.quarterLabel} {area.salesChangePct}% / {area.openings}곳", shop,
                Map.of("area.quarterLabel", "2022년 3분기", "area.salesChangePct", -12.2, "area.openings", 2)));
        assertEquals("", EventText.withoutTokens("{area.salesChangePct}{area.quarterLabel}{area.closings}"));
        assertEquals("{area.password}", EventText.withoutTokens("{area.password}"));
    }
}
