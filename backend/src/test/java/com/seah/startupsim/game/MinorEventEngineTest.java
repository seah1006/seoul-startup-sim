package com.seah.startupsim.game;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MinorEventEngineTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EventEngine engine = new EventEngine(null, null, new EventDefinitionValidator(), null, null, null, json);
    private final GameContext context = new GameContext(1, 1, 42, 4, "20231", null);

    private EventDefinitionRepository.Definition point(String code, String source) {
        return new EventDefinitionRepository.Definition(1, 1, json.readTree("""
                {"code":"%s","tier":"MINOR","source":"%s","conditionExpr":{},
                 "minTurn":0,"maxFires":3,"cooldownQuarters":2,"priority":50,
                 "baseProbability":1,"statBonus":[],"probMin":0,"probMax":1}
                """.formatted(code, source)));
    }
    private Map<String, Double> enabled() {
        return Map.of("event.minor.enabled", 1d, "event.minor.rate", 1d);
    }
    @Test void booleanSettingsUseNumericSnapshotWithoutAcceptingArbitraryBooleans() {
        var games = new GameRepository(null, json);
        assertThat(games.settingNumber("event.minor.enabled", "true")).isEqualTo(1d);
        assertThat(games.settingNumber("event.minor.dataFirst", "false")).isZero();
        org.junit.jupiter.api.Assertions.assertThrows(GameException.class,
                () -> games.settingNumber("initialCapital", "true"));
    }
    @Test void globalMajorGapDoesNotEraseDefinitionCooldown() {
        var definition = point("MIN_STORY", "STORY");
        Map<String, Object> history = new HashMap<>();
        history.put("history.majorQuartersSince", 999);
        history.put("history.quartersSince.MIN_STORY", 1);
        assertThat(engine.judge(context, history, Map.of(), List.of(definition), 1).get("MIN_STORY")).isFalse();
        history.put("history.quartersSince.MIN_STORY", 3);
        assertThat(engine.judge(context, history, Map.of(), List.of(definition), 1).get("MIN_STORY")).isTrue();
        history.put("history.majorQuartersSince", 1);
        assertThat(engine.judge(context, history, Map.of(), List.of(definition), 1).get("MIN_STORY")).isFalse();
    }
    @Test void missingSettingDisablesMinor() {
        assertThat(engine.pickMinor(context, new HashMap<>(), Map.of(), List.of(point("MIN_STORY", "STORY")), Map.of())).isNull();
    }
    @Test void zeroRateDisablesMinor() {
        assertThat(engine.pickMinor(context, new HashMap<>(), Map.of(), List.of(point("MIN_STORY", "STORY")),
                Map.of("event.minor.enabled", 1d, "event.minor.rate", 0d))).isNull();
    }
    @Test void dataFirstAndCap() {
        var story = point("MIN_STORY", "STORY");
        var data = point("MIN_DATA", "DATA");
        assertThat(engine.pickMinor(context, new HashMap<>(), Map.of(), List.of(story, data), enabled())).isEqualTo(data);
        assertThat(engine.pickMinor(context, new HashMap<>(Map.of("history.timesFired.MIN_DATA", 4)), Map.of(),
                List.of(story, data), enabled())).isEqualTo(story);
    }
    @Test void cooldownAndMaximumAreHonored() {
        var story = point("MIN_STORY", "STORY");
        for (var history : List.of(Map.of("history.timesFired.MIN_STORY", 3), Map.of("history.quartersSince.MIN_STORY", 1))) {
            assertThat(engine.pickMinor(context, new HashMap<>(history), Map.of(), List.of(story), enabled())).isNull();
        }
    }
    @Test void deterministicRegardlessOfInputOrder() {
        var first = point("MIN_FIRST", "STORY");
        var second = point("MIN_SECOND", "STORY");
        assertThat(engine.pickMinor(context, new HashMap<>(), Map.of(), List.of(first, second), enabled()))
                .isEqualTo(engine.pickMinor(context, new HashMap<>(), Map.of(), List.of(second, first), enabled()));
    }
}
