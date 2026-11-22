package com.seah.startupsim.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 결산과 종료 판정 뒤 순간 → 경쟁점 변화 → 동네 반응 → 전환점 순서로 이벤트를 만든다.
 * 활성 정의가 하나도 없으면 아무것도 하지 않아 이벤트 없는 경로의 결과 바이트를 보존한다.
 */
@Component
public class EventEngine implements TurnEventHook {
    private final GameRepository games;
    private final EventDefinitionRepository definitions;
    private final EventDefinitionValidator validator;
    private final EventContextFactory contexts;
    private final EventEffectService effects;
    private final NeighborhoodEventService neighborhood;
    private final ObjectMapper json;

    public EventEngine(GameRepository games, EventDefinitionRepository definitions, EventDefinitionValidator validator,
            EventContextFactory contexts, EventEffectService effects, NeighborhoodEventService neighborhood,
            ObjectMapper json) {
        this.games = games;
        this.definitions = definitions;
        this.validator = validator;
        this.contexts = contexts;
        this.effects = effects;
        this.neighborhood = neighborhood;
        this.json = json;
    }

    @Override
    public void afterQuarter(GameContext context, QuarterOutcome outcome, TurnResultBuilder result) {
        var points = definitions.turningPoints();
        var reactions = definitions.reactions();
        var moments = definitions.moments();
        if (points.isEmpty() && reactions.isEmpty() && moments.isEmpty()) {
            return;
        }
        Game game = games.game(context.gameId(), context.userId(), false);
        Shop shop = games.shop(context.gameId());
        Map<String, Object> variables = contexts.variables(context, outcome);
        if (!moments.isEmpty()) {
            var moment = neighborhood.moment(context, game, shop, variables, moments);
            if (moment != null) {
                result.moment(moment);
            }
        }
        // 가상 경쟁점 변화는 반응 문구로만 노출하므로 반응 템플릿이 꺼져 있으면 함께 멈춘다.
        String rivalNews = reactions.isEmpty() ? null : neighborhood.rivals(context, game, variables);
        if (!moments.isEmpty() || !reactions.isEmpty()) {
            variables = contexts.variables(context, outcome);
        }
        if (!reactions.isEmpty()) {
            var picked = neighborhood.reactions(context, game, shop,
                    contexts.reactionVariables(context, outcome, variables), reactions, rivalNews);
            result.reactions(new ArrayList<Object>(picked));
        }
        if (!context.ended() && !points.isEmpty()
                && !games.pendingTurningPoint(context.gameId())) {
            fire(context, variables, points, result, game.settings());
        }
    }

    private void fire(GameContext context, Map<String, Object> variables,
            List<EventDefinitionRepository.Definition> points, TurnResultBuilder result, Map<String, Double> settings) {
        Map<String, Integer> stats = games.stats(context.gameId());
        // MINOR는 MAJOR 전역 간격을 소비하지 않는다.
        var majorVariables = new LinkedHashMap<>(variables);
        points.stream().filter(point -> tier(point).equals("MINOR"))
                .forEach(point -> majorVariables.remove("history.quartersSince." + point.code()));
        if (variables.containsKey("history.majorQuartersSince")) {
            majorVariables.keySet().removeIf(key -> key.startsWith("history.quartersSince."));
            majorVariables.put("history.quartersSince.MAJOR", variables.get("history.majorQuartersSince"));
        }
        var majors = points.stream().filter(point -> tier(point).equals("MAJOR"))
                .filter(point -> context.turn() >= settings.getOrDefault("event.fire.minTurn", 1d)).toList();
        Map<String, Boolean> judged = judge(context, majorVariables, stats, majors,
                settings.getOrDefault("event.fire.globalGap", 0d));
        List<EventDefinitionRepository.Definition> candidates = new ArrayList<>();
        int highest = -1;
        for (var point : points) {
            if (!judged.getOrDefault(point.code(), false)) {
                continue;
            }
            int priority = point.data().get("priority").intValue();
            if (candidates.isEmpty()) {
                highest = priority;
            }
            if (priority == highest) {
                candidates.add(point);
            }
        }
        if (candidates.isEmpty()) {
            var minor = pickMinor(context, variables, stats, points, settings);
            if (minor == null) return;
            candidates.add(minor);
        }
        int picked = candidates.size() == 1 ? 0 : (int) (EventRandom.stream(context.seed(), context.turn(),
                EventRandom.Purpose.TURNING_POINT_PICK, "").nextDouble() * candidates.size());
        var definition = candidates.get(picked);
        String payload = json.writeValueAsString(freeze(definition, stats, games.shop(context.gameId()), variables));
        games.jdbc().update("""
                INSERT INTO game_turning_points (game_id, turn, turning_point_id, definition_rev, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, context.gameId(), context.turn(), definition.id(), definition.rev(), payload);
        result.pendingTurningPoint(true);
    }

    /**
     * 정의마다 자기 code 스트림의 첫 난수만 써서 발생 여부를 판정한다.
     * 정의를 추가·삭제하거나 순서를 바꿔도 다른 정의의 판정은 바뀌지 않는다.
     */
    Map<String, Boolean> judge(GameContext context, Map<String, Object> variables, Map<String, Integer> stats,
            List<EventDefinitionRepository.Definition> points) {
        return judge(context, variables, stats, points, 0);
    }

    Map<String, Boolean> judge(GameContext context, Map<String, Object> variables, Map<String, Integer> stats,
            List<EventDefinitionRepository.Definition> points, double globalGap) {
        int sinceAny = variables.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("history.quartersSince."))
                .mapToInt(entry -> ((Number) entry.getValue()).intValue()).min().orElse(Integer.MAX_VALUE);
        boolean gapPassed = globalGap <= 0 || sinceAny > globalGap;
        Map<String, Boolean> judged = new LinkedHashMap<>();
        for (var point : points) {
            JsonNode data = point.data();
            EventContextFactory.defaults(data.get("conditionExpr"), variables);
            int times = ((Number) variables.getOrDefault("history.timesFired." + point.code(), 0)).intValue();
            int since = ((Number) variables.getOrDefault("history.quartersSince." + point.code(), 999)).intValue();
            boolean eligible = gapPassed && context.turn() >= data.get("minTurn").intValue()
                    && times < data.get("maxFires").intValue()
                    && since >= data.get("cooldownQuarters").intValue()
                    && validator.condition(data.get("conditionExpr"), EventDefinitionValidator.Scope.TURNING_POINT)
                            .matches(variables);
            double draw = EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.TURNING_POINT_FIRE,
                    point.code()).nextDouble();
            judged.put(point.code(), eligible && draw < validator.formula(fireFormula(data)).probability(stats));
        }
        return judged;
    }

    static String tier(EventDefinitionRepository.Definition point) {
        return point.data().has("tier") ? point.data().get("tier").asString() : "MAJOR";
    }

    static String source(EventDefinitionRepository.Definition point) {
        return point.data().has("source") ? point.data().get("source").asString() : "STORY";
    }

    /** 별도 스트림을 사용하므로 MINOR 설정이 없으면 기존 골든 난수는 완전히 동일하다. */
    EventDefinitionRepository.Definition pickMinor(GameContext context, Map<String, Object> variables,
            Map<String, Integer> stats, List<EventDefinitionRepository.Definition> points,
            Map<String, Double> settings) {
        if (settings.getOrDefault("event.minor.enabled", 0d) <= 0
                || EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.MINOR_FIRE, "")
                    .nextDouble() >= settings.getOrDefault("event.minor.rate", 0.9)) return null;
        int dataFires = points.stream().filter(point -> source(point).equals("DATA"))
                .mapToInt(point -> ((Number) variables.getOrDefault("history.timesFired." + point.code(), 0)).intValue()).sum();
        dataFires = ((Number) variables.getOrDefault("history.dataFires", dataFires)).intValue();
        var eligible = new ArrayList<EventDefinitionRepository.Definition>();
        for (var point : points) {
            if (!tier(point).equals("MINOR")) continue;
            if (source(point).equals("DATA") && dataFires >= settings.getOrDefault("event.minor.dataMax", 4d)) continue;
            var data = point.data();
            EventContextFactory.defaults(data.get("conditionExpr"), variables);
            int times = ((Number) variables.getOrDefault("history.timesFired." + point.code(), 0)).intValue();
            int since = ((Number) variables.getOrDefault("history.quartersSince." + point.code(), 999)).intValue();
            if (context.turn() >= data.get("minTurn").intValue() && times < data.get("maxFires").intValue()
                    && since >= data.get("cooldownQuarters").intValue()
                    && validator.condition(data.get("conditionExpr"), EventDefinitionValidator.Scope.TURNING_POINT).matches(variables)
                    && validator.formula(fireFormula(data)).probability(stats) > 0) eligible.add(point);
        }
        if (settings.getOrDefault("event.minor.dataFirst", 1d) > 0
                && eligible.stream().anyMatch(point -> source(point).equals("DATA"))) {
            eligible.removeIf(point -> !source(point).equals("DATA"));
        }
        int priority = eligible.stream().mapToInt(point -> point.data().get("priority").intValue()).max().orElse(-1);
        eligible.removeIf(point -> point.data().get("priority").intValue() != priority);
        eligible.sort(java.util.Comparator.comparing(EventDefinitionRepository.Definition::code));
        double total = eligible.stream().mapToDouble(point -> validator.formula(fireFormula(point.data())).probability(stats)).sum();
        double draw = EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.MINOR_PICK, "").nextDouble() * total;
        for (var point : eligible) {
            draw -= validator.formula(fireFormula(point.data())).probability(stats);
            if (draw < 0) return point;
        }
        return null;
    }
    private static JsonNode fireFormula(JsonNode data) {
        var formula = JsonNodeFactory.instance.objectNode();
        formula.set("base", data.get("baseProbability"));
        formula.set("statBonus", data.get("statBonus"));
        formula.set("min", data.get("probMin"));
        formula.set("max", data.get("probMax"));
        return formula;
    }

    /** 화면 문구·성공률·효과 원본을 함께 고정해 이후 관리자 수정이 대기 중 선택을 바꾸지 못하게 한다. */
    private TurningPointService.FrozenPoint freeze(EventDefinitionRepository.Definition definition,
            Map<String, Integer> stats, Shop shop, Map<String, Object> variables) {
        var ids = definitions.optionIds(definition.id());
        List<TurningPointService.FrozenOption> options = new ArrayList<>();
        for (JsonNode option : definition.data().get("options")) {
            int order = option.get("orderNo").intValue();
            double probability = validator.formula(option.get("successFormula")).probability(stats);
            options.add(new TurningPointService.FrozenOption(ids.get(order), order, EventText.render(option.get("label").asString(), shop, variables),
                    probability, (int) Math.round(probability * 100),
                    effects.summary(validator.effects(option.get("onSuccess"), false, "onSuccess")),
                    effects.summary(validator.effects(option.get("onFail"), false, "onFail")),
                    option.get("onSuccess"), option.get("onFail"), EventText.render(option.get("successText").asString(), shop, variables),
                    EventText.render(option.get("failText").asString(), shop, variables)));
        }
        return new TurningPointService.FrozenPoint(definition.code(), EventText.render(definition.data().get("title").asString(), shop, variables),
                EventText.render(definition.data().get("description").asString(), shop, variables), List.copyOf(options),
                tier(definition), source(definition), source(definition).equals("DATA")
                    ? "서울시 상권분석서비스 " + variables.get("area.quarterLabel") + " 실제 값" : null);
    }
}
