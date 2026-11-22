package com.seah.startupsim.game;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/** 밸런스 봇의 공개 휴리스틱. 현금 단위 기대효과로 선택하고 동점은 정의 순서를 유지한다. */
final class MinorBotPolicy {
    private MinorBotPolicy() {}
    static JsonNode choose(JsonNode options, Map<String, Integer> stats, EventDefinitionValidator validator) {
        JsonNode best = options.get(0);
        double highest = -Double.MAX_VALUE;
        for (JsonNode option : options) {
            double p = validator.formula(option.get("successFormula")).probability(stats);
            double score = p * utility(option.get("onSuccess")) + (1 - p) * utility(option.get("onFail"));
            if (score > highest) { highest = score; best = option; }
        }
        return best;
    }
    static double utility(JsonNode effects) {
        double score = 0;
        for (JsonNode effect : effects) {
            score += switch (effect.get("type").asString()) {
                case "CASH" -> effect.get("amount").doubleValue();
                case "STAT" -> effect.get("delta").doubleValue() * 150000;
                case "SHOP_COUNT" -> effect.get("delta").doubleValue() * 10000000;
                case "MODIFIER" -> {
                    String target = effect.get("target").asString();
                    double value = effect.get("value").doubleValue();
                    int duration = effect.get("quarters").intValue();
                    double benefit = effect.get("mode").asString().equals("MULTIPLY") ? value - 1 : value;
                    yield benefit * duration * (target.equals("rent") || target.equals("costRate") ? -10000000 : 10000000);
                }
                default -> 0;
            };
        }
        return score;
    }
}
