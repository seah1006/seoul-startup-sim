package com.seah.startupsim.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MinorBotPolicyTest {
    @Test void choosesExpectedNetEffectInsteadOfHighestSuccessRate() {
        var options = new ObjectMapper().readTree("""
                [{"orderNo":1,"successFormula":{"base":1,"min":0,"max":1},
                  "onSuccess":[{"type":"CASH","amount":-100}],"onFail":[]},
                 {"orderNo":2,"successFormula":{"base":0.5,"min":0,"max":1},
                  "onSuccess":[{"type":"CASH","amount":1000}],"onFail":[{"type":"CASH","amount":-100}]}]
                """);
        assertEquals(2, MinorBotPolicy.choose(options, Map.of(), new EventDefinitionValidator()).get("orderNo").intValue());
    }
}
