package com.seah.startupsim.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** 순간·동네 반응·가상 경쟁점 변화를 목적별 독립 난수로 결정한다. 현금은 건드리지 않는다. */
@Service
public class NeighborhoodEventService {
    public record Moment(String title, String text, String stat, boolean success) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reaction(String kind, String author, Integer rating, String text, int likes) {}

    private static final String OPENED = "같은 업종 가게가 동네에 한 곳 늘었어요. 손님 나눠 갖게 생겼네요.";
    private static final String CLOSED = "근처 같은 업종 가게 한 곳이 문을 닫았대요. 골목 분위기가 조금 달라졌어요.";

    private final JdbcTemplate jdbc;
    private final EventDefinitionValidator validator;
    private final EventEffectService effects;

    public NeighborhoodEventService(JdbcTemplate jdbc, EventDefinitionValidator validator, EventEffectService effects) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.effects = effects;
    }

    /** 조건을 만족한 순간 하나를 가중 추첨하고 능력치 판정 결과의 작은 효과만 적용한다. */
    public Moment moment(GameContext context, Game game, Shop shop, Map<String, Object> variables,
            List<EventDefinitionRepository.Definition> definitions) {
        List<EventDefinitionRepository.Definition> candidates = new ArrayList<>();
        for (var definition : definitions) {
            if (matches(definition.data().get("conditionExpr"), EventDefinitionValidator.Scope.MOMENT, variables)) {
                candidates.add(definition);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        double draw = EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.MOMENT_PICK, "").nextDouble();
        var picked = candidates.get(weighted(candidates, draw));
        JsonNode data = picked.data();
        String stat = data.get("stat").asString();
        Map<String, Double> settings = game.settings();
        double rate = 0.5 + (((Number) variables.get("stat." + stat)).intValue() - data.get("difficulty").intValue())
                * settings.getOrDefault("event.moment.statSlope", 0.02);
        rate = Math.max(settings.getOrDefault("event.moment.minRate", 0.05),
                Math.min(settings.getOrDefault("event.moment.maxRate", 0.95), rate));
        boolean success = EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.MOMENT_JUDGE,
                picked.code()).nextDouble() < rate;
        String key = success ? "onSuccess" : "onFail";
        effects.apply(context.gameId(), context.turn(), "MOMENT", picked.id(), validator.effects(data.get(key), true, key));
        String text = render(data.get(success ? "successText" : "failText").asString(), shop);
        return new Moment(data.get("title").asString(), text, stat, success);
    }

    /**
     * 실제 데이터의 개업·폐업 비율로 가상 경쟁점만 늘리거나 닫는다. 실제 점포 행은 닫지 않는다.
     * 반환값은 반응 문구로만 노출할 소식이며 변화가 없으면 null이다.
     */
    public String rivals(GameContext context, Game game, Map<String, Object> variables) {
        if (!(variables.get("area.storeCount") instanceof Number stores)) {
            return null;
        }
        long storeCount = Math.max(1, stores.longValue());
        long openings = variables.get("area.openingCount") instanceof Number number ? number.longValue() : 0;
        long closings = variables.get("area.closingCount") instanceof Number number ? number.longValue() : 0;
        String news = null;
        if (openings >= 1) {
            double rate = Math.min(0.6, openings / (double) storeCount);
            int cap = game.settings().getOrDefault("event.rival.virtualPerQuarterCap", 2d).intValue();
            SplittableRandom random = EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.VIRTUAL_RIVAL, "");
            for (int index = 0; index < cap && random.nextDouble() < rate; index++) {
                jdbc.update("""
                        INSERT INTO rivals (game_id, is_virtual, service_code, geom, is_franchise, opened_turn)
                        SELECT game_id, true, service_code, ST_Translate(geom, 0, ?), false, ?
                        FROM shops WHERE game_id = ?
                        """, (index + 1) * 0.0001, context.turn() + 1, context.gameId());
                news = OPENED;
            }
        }
        if (closings >= 1 && EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.RIVAL_CLOSURE, "")
                .nextDouble() < Math.min(0.5, closings / (double) storeCount)) {
            int closed = jdbc.update("""
                    UPDATE rivals SET closed_turn = ?
                    WHERE id = (SELECT id FROM rivals
                        WHERE game_id = ? AND is_virtual AND sbiz_store_id IS NULL AND closed_turn IS NULL
                        ORDER BY opened_turn, id LIMIT 1)
                    """, context.turn() + 1, context.gameId());
            if (closed > 0) {
                news = news == null ? CLOSED : news;
            }
        }
        return news;
    }

    /** 리뷰를 먼저 한 개 뽑고, 경쟁점 소식이 있으면 남은 자리 하나를 그 소식에 쓴다. */
    public List<Reaction> reactions(GameContext context, Game game, Shop shop, Map<String, Object> variables,
            List<EventDefinitionRepository.Definition> definitions, String rivalNews) {
        List<EventDefinitionRepository.Definition> candidates = new ArrayList<>();
        for (var definition : definitions) {
            if (matches(definition.data().get("conditionExpr"), EventDefinitionValidator.Scope.REACTION, variables)) {
                candidates.add(definition);
            }
        }
        if (candidates.isEmpty() && rivalNews == null) {
            return List.of();
        }
        boolean up = "UP".equals(variables.get("bucket.profit"));
        int min = game.settings().getOrDefault("event.reaction.min", 2d).intValue();
        int max = game.settings().getOrDefault("event.reaction.max", 3d).intValue();
        SplittableRandom random = EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.REACTION_PICK, "");
        int count = min + random.nextInt(Math.max(1, max - min + 1));
        int slots = rivalNews == null ? count : count - 1;
        List<Reaction> reactions = new ArrayList<>();
        while (reactions.size() < slots && !candidates.isEmpty()) {
            List<EventDefinitionRepository.Definition> pool = reactions.isEmpty()
                    ? candidates.stream().filter(item -> item.data().get("kind").asString().equals("REVIEW")).toList()
                    : candidates;
            pool = pool.isEmpty() ? candidates : pool;
            var picked = pool.get(weighted(pool, random.nextDouble()));
            candidates.remove(picked);
            JsonNode data = picked.data();
            JsonNode rating = data.get("rating");
            reactions.add(new Reaction(data.get("kind").asString(), data.get("author").asString(),
                    rating == null || rating.isNull() ? null : rating.intValue(),
                    render(data.get("text").asString(), shop), likes(context, picked.code(), up)));
        }
        if (rivalNews != null) {
            reactions.add(new Reaction("COMMUNITY", "동네 커뮤니티", null, rivalNews, likes(context, "RIVAL_NEWS", up)));
        }
        return List.copyOf(reactions);
    }

    private int likes(GameContext context, String code, boolean up) {
        int likes = 3 + EventRandom.stream(context.seed(), context.turn(), EventRandom.Purpose.REACTION_LIKES, code).nextInt(60);
        return up ? likes * 2 : likes;
    }

    private boolean matches(JsonNode condition, EventDefinitionValidator.Scope scope, Map<String, Object> variables) {
        EventContextFactory.defaults(condition, variables);
        return validator.condition(condition, scope).matches(variables);
    }

    /** 코드순으로 정렬된 후보의 누적 가중치에서 하나를 고른다. */
    static int weighted(List<EventDefinitionRepository.Definition> candidates, double draw) {
        int total = 0;
        for (var candidate : candidates) {
            total += weight(candidate);
        }
        double target = draw * total;
        int cumulative = 0;
        for (int index = 0; index < candidates.size(); index++) {
            cumulative += weight(candidates.get(index));
            if (target < cumulative) {
                return index;
            }
        }
        return candidates.size() - 1;
    }

    private static int weight(EventDefinitionRepository.Definition definition) {
        JsonNode weight = definition.data().get("weight");
        return weight == null || weight.isNull() ? 10 : weight.intValue();
    }

    /** 허용된 세 치환 변수만 바꾼다. 실제 점포 상호는 쓰지 않는다. */
    static String render(String text, Shop shop) {
        return text.replace("{shopName}", shop.shopName()).replace("{areaName}", shop.areaName())
                .replace("{serviceName}", shop.serviceName());
    }
}
