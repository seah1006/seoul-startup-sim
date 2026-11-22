package com.seah.startupsim.game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 저장과 로드에서 공용으로 사용하는 화이트리스트 DSL 검증기. 오류에 JSON 경로를 남긴다. */
@Component
public final class EventDefinitionValidator {
    public enum Scope { TURNING_POINT, REACTION, MOMENT }
    private enum ValueType { NUMBER, STRING, BOOLEAN }
    private static final Set<String> NUMBERS = Set.of("quartersOperated", "negativeQuarters", "quarterOfYear",
            "cash", "cumulativeProfit", "lastProfit", "lastRevenue", "areaRank", "areaRankTotal", "areaRankPct",
            "staff", "openHours", "marketingBudget", "shopCount", "rivalCount", "area.storeCount",
            "area.franchiseCount", "area.openingCount", "area.closingCount", "area.openingRate",
            "area.closingRate", "area.closingRateDelta", "area.salesTrendPct");
    private static final Set<String> STRINGS = Set.of("priceLevel", "industryCategory", "serviceCode", "area.areaType");
    private static final Map<String, Set<String>> ENUMS = Map.of(
            "priceLevel", Set.of("LOW", "NORMAL", "HIGH"),
            "industryCategory", Set.of("FOOD", "SERVICE", "RETAIL"),
            "bucket.profit", Set.of("UP", "FLAT", "DOWN", "LOSS"),
            "bucket.rank", Set.of("UP", "SAME", "DOWN"),
            "bucket.visitors", Set.of("UP", "FLAT", "DOWN"),
            "bucket.price", Set.of("LOW", "NORMAL", "HIGH"),
            "bucket.hours", Set.of("SHORT", "NORMAL", "LONG"),
            "lastResolved.result", Set.of("SUCCESS", "FAIL", "NONE"));

    public EventDsl.Condition condition(JsonNode node, Scope scope) {
        return condition(node, scope, "conditionExpr", 1, new int[]{0});
    }

    private EventDsl.Condition condition(JsonNode node, Scope scope, String path, int depth, int[] count) {
        object(node, path);
        require(depth <= 4 && ++count[0] <= 30, path, "조건 깊이 4 또는 노드 30 상한을 넘었습니다.");
        if (node.isEmpty()) {
            return new EventDsl.Always();
        }
        if (node.has("all") || node.has("any")) {
            String key = node.has("all") ? "all" : "any";
            keys(node, path, Set.of(key), Set.of(key));
            JsonNode children = node.get(key);
            array(children, path + "." + key, 20);
            List<EventDsl.Condition> parsed = new ArrayList<>();
            for (int index = 0; index < children.size(); index++) {
                parsed.add(condition(children.get(index), scope, path + "." + key + "[" + index + "]", depth + 1, count));
            }
            return new EventDsl.Group(key.equals("all"), parsed);
        }
        if (node.has("not")) {
            keys(node, path, Set.of("not"), Set.of("not"));
            return new EventDsl.Negation(condition(node.get("not"), scope, path + ".not", depth + 1, count));
        }
        keys(node, path, Set.of("var", "op", "value"), Set.of("var", "op", "value"));
        String variable = text(node.get("var"), path + ".var", 1, 100);
        ValueType type = variableType(variable, scope, path + ".var");
        String operator = text(node.get("op"), path + ".op", 1, 10);
        Set<String> operators = switch (type) {
            case NUMBER -> Set.of("==", "!=", ">", ">=", "<", "<=", "in", "between");
            case STRING -> Set.of("==", "!=", "in");
            case BOOLEAN -> Set.of("==", "!=");
        };
        require(operators.contains(operator), path + ".op", "허용되지 않은 연산자입니다.");
        JsonNode value = node.get("value");
        if (operator.equals("in") || operator.equals("between")) {
            array(value, path + ".value", 20);
            require(!value.isEmpty(), path + ".value", "빈 비교 배열입니다.");
            require(!operator.equals("between") || value.size() == 2, path + ".value", "범위에는 양끝 값이 필요합니다.");
            List<Object> literals = new ArrayList<>();
            for (int index = 0; index < value.size(); index++) {
                literals.add(literal(value.get(index), type, variable, path + ".value[" + index + "]"));
            }
            if (operator.equals("between")) {
                require(((Number) literals.get(0)).doubleValue() <= ((Number) literals.get(1)).doubleValue(),
                        path + ".value", "범위의 순서가 잘못되었습니다.");
            }
            return new EventDsl.Comparison(variable, operator, List.copyOf(literals));
        }
        return new EventDsl.Comparison(variable, operator, literal(value, type, variable, path + ".value"));
    }

    private ValueType variableType(String variable, Scope scope, String path) {
        if (NUMBERS.contains(variable) || variable.matches("history\\.(timesFired|quartersSince)\\.[A-Z][A-Z0-9_]{2,39}")
                || variable.startsWith("stat.") && OwnerCandidates.STATS.contains(variable.substring(5))) {
            return ValueType.NUMBER;
        }
        if (STRINGS.contains(variable)) {
            return ValueType.STRING;
        }
        if (variable.matches("flag\\.[A-Z][A-Z0-9_]{0,39}")) {
            return ValueType.BOOLEAN;
        }
        if (scope == Scope.REACTION) {
            if (variable.equals("bucket.firstQuarter")) {
                return ValueType.BOOLEAN;
            }
            if (variable.equals("lastResolved.quartersSince")) {
                return ValueType.NUMBER;
            }
            if (ENUMS.containsKey(variable) || variable.equals("lastResolved.code")) {
                return ValueType.STRING;
            }
        }
        throw invalid(path, "알 수 없거나 이 정의에서 허용되지 않은 변수입니다.");
    }

    private Object literal(JsonNode node, ValueType type, String variable, String path) {
        return switch (type) {
            case NUMBER -> {
                number(node, path, -Double.MAX_VALUE, Double.MAX_VALUE);
                yield node.decimalValue();
            }
            case BOOLEAN -> bool(node, path);
            case STRING -> {
                String value = text(node, path, 0, 300);
                require(!ENUMS.containsKey(variable) || ENUMS.get(variable).contains(value), path, "허용되지 않은 값입니다.");
                yield value;
            }
        };
    }

    public EventDsl.Formula formula(JsonNode node) {
        return formula(node, "successFormula");
    }

    private EventDsl.Formula formula(JsonNode node, String path) {
        keys(node, path, Set.of("base", "statBonus", "min", "max"), Set.of("base", "min", "max"));
        double base = number(node.get("base"), path + ".base", 0, 1);
        double min = number(node.get("min"), path + ".min", 0, 1);
        double max = number(node.get("max"), path + ".max", min, 1);
        List<EventDsl.StatBonus> bonuses = new ArrayList<>();
        if (node.has("statBonus")) {
            JsonNode list = node.get("statBonus");
            array(list, path + ".statBonus", 2);
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < list.size(); index++) {
                String itemPath = path + ".statBonus[" + index + "]";
                JsonNode item = list.get(index);
                keys(item, itemPath, Set.of("stat", "per10"), Set.of("stat", "per10"));
                String stat = stat(item.get("stat"), itemPath + ".stat");
                require(seen.add(stat), itemPath + ".stat", "중복 능력치입니다.");
                bonuses.add(new EventDsl.StatBonus(stat, number(item.get("per10"), itemPath + ".per10", -0.2, 0.2)));
            }
        }
        return new EventDsl.Formula(base, bonuses, min, max);
    }

    public List<EventDsl.Effect> effects(JsonNode node, boolean moment, String path) {
        array(node, path, 20);
        List<EventDsl.Effect> effects = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            String p = path + "[" + index + "]";
            object(item, p);
            String type = text(item.get("type"), p + ".type", 1, 30);
            require(!moment || Set.of("STAT", "MODIFIER", "TEXT").contains(type), p + ".type", "순간에 허용되지 않은 효과입니다.");
            EventDsl.Effect effect = switch (type) {
                case "CASH" -> {
                    exact(item, p, "type", "amount", "memo");
                    yield new EventDsl.Cash(integer(item.get("amount"), p + ".amount", -500000000, 500000000),
                            text(item.get("memo"), p + ".memo", 1, 300));
                }
                case "STAT" -> {
                    exact(item, p, "type", "stat", "delta");
                    yield new EventDsl.Stat(stat(item.get("stat"), p + ".stat"),
                            (int) integer(item.get("delta"), p + ".delta", moment ? -2 : -10, moment ? 2 : 10));
                }
                case "MODIFIER" -> {
                    exact(item, p, "type", "target", "mode", "value", "quarters", "label");
                    String target = choice(item.get("target"), p + ".target", Set.of("revenue", "costRate", "rent", "visitors"));
                    String mode = choice(item.get("mode"), p + ".mode", Set.of("MULTIPLY", "ADD"));
                    double value = number(item.get("value"), p + ".value", mode.equals("MULTIPLY") ? 0.1 : -Double.MAX_VALUE,
                            mode.equals("MULTIPLY") ? 3 : Double.MAX_VALUE);
                    yield new EventDsl.ModifierEffect(target, mode, value,
                            (int) integer(item.get("quarters"), p + ".quarters", 1, moment ? 2 : 8),
                            text(item.get("label"), p + ".label", 1, 40));
                }
                case "FLAG" -> {
                    exact(item, p, "type", "flag", "value");
                    String flag = text(item.get("flag"), p + ".flag", 1, 40);
                    require(flag.matches("[A-Z][A-Z0-9_]{0,39}"), p + ".flag", "플래그 형식이 잘못되었습니다.");
                    yield new EventDsl.Flag(flag, bool(item.get("value"), p + ".value"));
                }
                case "SHOP_COUNT" -> {
                    exact(item, p, "type", "delta");
                    int delta = (int) integer(item.get("delta"), p + ".delta", -1, 1);
                    require(delta != 0, p + ".delta", "증감은 -1 또는 1입니다.");
                    yield new EventDsl.ShopCount(delta);
                }
                case "RIVAL_ADD" -> {
                    exact(item, p, "type", "count", "franchise");
                    yield new EventDsl.RivalAdd((int) integer(item.get("count"), p + ".count", 1, 3),
                            bool(item.get("franchise"), p + ".franchise"));
                }
                case "TEXT" -> {
                    exact(item, p, "type", "text");
                    yield new EventDsl.Text(text(item.get("text"), p + ".text", 1, 300));
                }
                default -> throw invalid(p + ".type", "알 수 없는 효과입니다.");
            };
            effects.add(effect);
        }
        return List.copyOf(effects);
    }

    public void turningPoint(JsonNode node) {
        keys(node, "turningPoint", Set.of("code", "title", "description", "conditionExpr", "baseProbability", "statBonus",
                "probMin", "probMax", "cooldownQuarters", "maxFires", "minTurn", "priority", "enabled", "options", "tier", "source"),
                Set.of("code", "title", "conditionExpr", "baseProbability", "options"));
        code(node);
        text(node.get("title"), "title", 2, 60);
        if (node.has("description")) {
            text(node.get("description"), "description", 0, 400);
        }
        condition(node.get("conditionExpr"), Scope.TURNING_POINT);
        number(node.get("baseProbability"), "baseProbability", 0, 1);
        var formula = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        formula.set("base", node.get("baseProbability"));
        formula.put("min", node.has("probMin") ? number(node.get("probMin"), "probMin", 0, 1) : 0);
        formula.put("max", node.has("probMax") ? number(node.get("probMax"), "probMax", 0, 1) : 1);
        if (node.has("statBonus")) {
            formula.set("statBonus", node.get("statBonus"));
        }
        formula(formula, "fireFormula");
        if (node.has("tier")) choice(node.get("tier"), "tier", Set.of("MAJOR", "MINOR"));
        if (node.has("source")) choice(node.get("source"), "source", Set.of("STORY", "DATA"));
        optionalInteger(node, "cooldownQuarters", 0, 20);
        optionalInteger(node, "maxFires", 1, 10);
        optionalInteger(node, "minTurn", 0, 40);
        optionalInteger(node, "priority", 0, 1000);
        enabled(node);
        JsonNode options = node.get("options");
        array(options, "options", 3);
        require(options.size() >= 2, "options", "선택지는 2~3개입니다.");
        Set<Long> orders = new HashSet<>();
        for (int index = 0; index < options.size(); index++) {
            String p = "options[" + index + "]";
            JsonNode option = options.get(index);
            exact(option, p, "orderNo", "label", "successFormula", "onSuccess", "onFail", "successText", "failText");
            require(orders.add(integer(option.get("orderNo"), p + ".orderNo", 1, 3)), p + ".orderNo", "중복 순서입니다.");
            text(option.get("label"), p + ".label", 1, 40);
            formula(option.get("successFormula"), p + ".successFormula");
            effects(option.get("onSuccess"), false, p + ".onSuccess");
            effects(option.get("onFail"), false, p + ".onFail");
            text(option.get("successText"), p + ".successText", 1, 300);
            text(option.get("failText"), p + ".failText", 1, 300);
        }
    }

    public void reaction(JsonNode node) {
        keys(node, "reaction", Set.of("code", "kind", "author", "rating", "weight", "enabled", "conditionExpr", "text"),
                Set.of("code", "kind", "author", "conditionExpr", "text"));
        code(node);
        String kind = choice(node.get("kind"), "kind", Set.of("REVIEW", "COMMUNITY"));
        text(node.get("author"), "author", 1, 40);
        if (kind.equals("REVIEW")) {
            integer(node.get("rating"), "rating", 1, 5);
        } else {
            require(!node.has("rating") || node.get("rating").isNull(), "rating", "커뮤니티에는 별점이 없습니다.");
        }
        optionalInteger(node, "weight", 1, 100);
        enabled(node);
        condition(node.get("conditionExpr"), Scope.REACTION);
        tokens(text(node.get("text"), "text", 2, 300), "text");
    }

    /** 치환 변수는 shopName·areaName·serviceName과 선택 조사 쌍({shopName:이/가} 등)만 허용한다. */
    private void tokens(String text, String path) {
        String rest = EventText.withoutTokens(text);
        require(!rest.contains("{") && !rest.contains("}"), path, "허용되지 않은 치환 변수입니다.");
    }

    public void moment(JsonNode node) {
        keys(node, "moment", Set.of("code", "title", "stat", "difficulty", "weight", "enabled", "conditionExpr",
                "successText", "failText", "onSuccess", "onFail"),
                Set.of("code", "title", "stat", "difficulty", "conditionExpr", "successText", "failText", "onSuccess", "onFail"));
        code(node);
        text(node.get("title"), "title", 2, 60);
        stat(node.get("stat"), "stat");
        integer(node.get("difficulty"), "difficulty", 20, 80);
        optionalInteger(node, "weight", 1, 100);
        enabled(node);
        condition(node.get("conditionExpr"), Scope.MOMENT);
        tokens(text(node.get("successText"), "successText", 2, 300), "successText");
        tokens(text(node.get("failText"), "failText", 2, 300), "failText");
        effects(node.get("onSuccess"), true, "onSuccess");
        effects(node.get("onFail"), true, "onFail");
    }

    private void code(JsonNode node) {
        require(text(node.get("code"), "code", 3, 40).matches("[A-Z][A-Z0-9_]{2,39}"), "code", "코드 형식이 잘못되었습니다.");
    }

    private void enabled(JsonNode node) {
        if (node.has("enabled")) {
            bool(node.get("enabled"), "enabled");
        }
    }

    private void optionalInteger(JsonNode node, String key, int min, int max) {
        if (node.has(key)) {
            integer(node.get(key), key, min, max);
        }
    }

    private String stat(JsonNode node, String path) {
        return choice(node, path, Set.copyOf(OwnerCandidates.STATS));
    }

    private String choice(JsonNode node, String path, Set<String> choices) {
        String value = text(node, path, 1, 100);
        require(choices.contains(value), path, "허용되지 않은 값입니다.");
        return value;
    }

    private void exact(JsonNode node, String path, String... keys) {
        keys(node, path, Set.of(keys), Set.of(keys));
    }

    private void keys(JsonNode node, String path, Set<String> allowed, Set<String> required) {
        object(node, path);
        for (var entry : node.properties()) {
            require(allowed.contains(entry.getKey()), path + "." + entry.getKey(), "알 수 없는 키입니다.");
        }
        for (String key : required) {
            require(node.has(key), path + "." + key, "필수 값입니다.");
        }
    }

    private void object(JsonNode node, String path) {
        require(node != null && node.isObject(), path, "객체여야 합니다.");
    }

    private void array(JsonNode node, String path, int max) {
        require(node != null && node.isArray() && node.size() <= max, path, "배열 형식 또는 원소 수 상한 위반입니다.");
    }

    private String text(JsonNode node, String path, int min, int max) {
        require(node != null && node.isString(), path, "문자열이어야 합니다.");
        String text = node.asString();
        int length = text.codePointCount(0, text.length());
        require(length >= min && length <= max, path, "문자열 길이 위반입니다.");
        return text;
    }

    private boolean bool(JsonNode node, String path) {
        require(node != null && node.isBoolean(), path, "참/거짓이어야 합니다.");
        return node.booleanValue();
    }

    private double number(JsonNode node, String path, double min, double max) {
        require(node != null && node.isNumber(), path, "숫자여야 합니다.");
        double value = node.doubleValue();
        require(Double.isFinite(value) && value >= min && value <= max, path, "숫자 범위 위반입니다.");
        return value;
    }

    private long integer(JsonNode node, String path, long min, long max) {
        number(node, path, min, max);
        require(node.isIntegralNumber() && node.canConvertToLong(), path, "정수여야 합니다.");
        return node.longValue();
    }

    private void require(boolean valid, String path, String message) {
        if (!valid) {
            throw invalid(path, message);
        }
    }

    private GameException invalid(String path, String message) {
        return new GameException(400, "INVALID_EXPRESSION", path + ": " + message);
    }
}
