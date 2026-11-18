package com.seah.startupsim.game;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 조건·확률·효과의 정상 경로와 악성 입력을 데이터로 검증한다. */
class EventDslTest {
    private final EventDefinitionValidator validator = new EventDefinitionValidator();
    private final ObjectMapper json = new ObjectMapper();

    private EventDsl.Condition condition(String input) {
        return validator.condition(json.readTree(input), EventDefinitionValidator.Scope.TURNING_POINT);
    }

    private void rejected(String input, String path) {
        GameException error = assertThrows(GameException.class, () -> condition(input));
        assertEquals("INVALID_EXPRESSION", error.code);
        assertTrue(error.getMessage().contains(path), error.getMessage());
    }

    @Test
    void allComparisonOperatorsAndMissingValues() {
        Map<String, Object> values = Map.of("cash", 100L);
        for (String op : List.of("==", ">=", "<=")) {
            assertTrue(condition("{\"var\":\"cash\",\"op\":\"" + op + "\",\"value\":100}").matches(values));
        }
        for (String op : List.of("!=", ">", "<")) {
            assertFalse(condition("{\"var\":\"cash\",\"op\":\"" + op + "\",\"value\":100}").matches(values));
        }
        assertTrue(condition("{\"var\":\"cash\",\"op\":\"in\",\"value\":[99,100]}").matches(values));
        assertTrue(condition("{\"var\":\"cash\",\"op\":\"between\",\"value\":[100,200]}").matches(values));
        assertFalse(condition("{\"var\":\"cash\",\"op\":\"!=\",\"value\":0}").matches(Map.of()));
        assertTrue(condition("{}").matches(Map.of()));
        assertTrue(condition("{\"not\":{\"var\":\"cash\",\"op\":\"==\",\"value\":0}}").matches(Map.of()));
        assertTrue(condition("{\"all\":[{}, {\"any\":[{},{}]}]}").matches(values));
        assertFalse(condition("{\"any\":[]}").matches(values));
    }

    @Test
    void rejectsUnknownKeysVariablesOperatorsAndLiteralTypes() {
        rejected("{\"sql\":\"DELETE FROM games\"}", "conditionExpr.sql");
        rejected("{\"all\":[],\"any\":[]}", "conditionExpr.any");
        rejected("{\"var\":\"user.password\",\"op\":\"==\",\"value\":1}", ".var");
        rejected("{\"var\":\"cash\",\"op\":\"eval\",\"value\":1}", ".op");
        rejected("{\"var\":\"cash\",\"op\":\"==\",\"value\":\"100\"}", ".value");
        rejected("{\"var\":\"flag.ABC\",\"op\":\">\",\"value\":true}", ".op");
        rejected("{\"var\":\"priceLevel\",\"op\":\"==\",\"value\":\"CHEAP\"}", ".value");
        rejected("{\"var\":\"bucket.profit\",\"op\":\"==\",\"value\":\"UP\"}", ".var");
        rejected("{\"var\":\"cash\",\"op\":\"between\",\"value\":[2,1]}", ".value");
        assertTrue(validator.condition(json.readTree("{\"var\":\"bucket.profit\",\"op\":\"==\",\"value\":\"UP\"}"),
                EventDefinitionValidator.Scope.REACTION).matches(Map.of("bucket.profit", "UP")));
    }

    @Test
    void rejectsDepthNodeAndArrayOverflows() {
        rejected("{\"not\":{\"not\":{\"not\":{\"not\":{}}}}}", ".not");
        String twenty = String.join(",", java.util.Collections.nCopies(20, "{}"));
        rejected("{\"all\":[" + twenty + ",{}]}", ".all");
        rejected("{\"all\":[{\"all\":[" + twenty + "]},{\"all\":[" + twenty + "]}]}", ".all");
    }

    @Test
    void formulasClampRoundAndUseFortyAsBaseline() {
        var formula = validator.formula(json.readTree("""
                {"base":0.355,"statBonus":[{"stat":"network","per10":0.1}],"min":0.1,"max":0.9}
                """));
        assertEquals(0.355, formula.probability(Map.of("network", 40)));
        assertEquals(36, formula.ratePct(Map.of("network", 40)));
        assertEquals(0.1, formula.probability(Map.of("network", 0)));
        assertEquals(0.9, formula.probability(Map.of("network", 100)));
        var negative = validator.formula(json.readTree("""
                {"base":0.5,"statBonus":[{"stat":"network","per10":-0.1}],"min":0,"max":1}
                """));
        assertEquals(0.4, negative.probability(Map.of("network", 50)), 0.000001);
        for (String invalid : List.of("{\"base\":2,\"min\":0,\"max\":1}",
                "{\"base\":0.5,\"min\":0.9,\"max\":0.1}",
                "{\"base\":0.5,\"min\":0,\"max\":1,\"statBonus\":[{\"stat\":\"network\",\"per10\":0.3}]}")) {
            assertThrows(GameException.class, () -> validator.formula(json.readTree(invalid)));
        }
    }

    @Test
    void parsesEveryEffectAndRejectsUnknownKeysAndMomentCash() {
        var effects = validator.effects(json.readTree("""
                [{"type":"CASH","amount":-100,"memo":"비용"},
                 {"type":"STAT","stat":"cooking","delta":2},
                 {"type":"MODIFIER","target":"revenue","mode":"MULTIPLY","value":1.2,"quarters":2,"label":"매출"},
                 {"type":"FLAG","flag":"SAMPLE","value":true},
                 {"type":"SHOP_COUNT","delta":1},
                 {"type":"RIVAL_ADD","count":1,"franchise":false},
                 {"type":"TEXT","text":"안내"}]
                """), false, "effects");
        assertEquals(7, effects.size());
        assertEquals(new EventDsl.Cash(-100, "비용"), effects.getFirst());
        for (String effect : List.of("{\"type\":\"CASH\",\"amount\":1,\"memo\":\"현금\"}",
                "{\"type\":\"STAT\",\"stat\":\"cooking\",\"delta\":3}",
                "{\"type\":\"FLAG\",\"flag\":\"ABC\",\"value\":true}",
                "{\"type\":\"MODIFIER\",\"target\":\"revenue\",\"mode\":\"ADD\",\"value\":1,\"quarters\":3,\"label\":\"보정\"}")) {
            assertThrows(GameException.class, () -> validator.effects(json.readTree("[" + effect + "]"), true, "effects"));
        }
        for (String effect : List.of("{\"type\":\"SQL\",\"text\":\"SELECT 1\"}",
                "{\"type\":\"STAT\",\"stat\":\"cooking\",\"delta\":11}",
                "{\"type\":\"CASH\",\"amount\":500000001,\"memo\":\"현금\"}",
                "{\"type\":\"CASH\",\"amount\":1.5,\"memo\":\"현금\"}",
                "{\"type\":\"TEXT\",\"text\":\"안내\",\"script\":\"run()\"}")) {
            assertThrows(GameException.class, () -> validator.effects(json.readTree("[" + effect + "]"), false, "effects"));
        }
    }
}
