package com.seah.startupsim.game;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 대기 시 정의 스냅샷만으로 선택을 해결하고 게임 행 잠금으로 쓰기를 직렬화한다. */
@Service
public class TurningPointService {
    public record FrozenOption(long optionId, int orderNo, String label, double probability, int successRatePct,
            String onSuccess, String onFail, JsonNode successEffects, JsonNode failEffects, String successText, String failText) {}
    public record FrozenPoint(String code, String title, String description, List<FrozenOption> options) {}
    public record Pending(long id, int turn, FrozenPoint payload) {}
    public record OptionView(long optionId, String label, int successRatePct, String onSuccess, String onFail) {}
    public record View(long gameTurningPointId, String code, String title, String description, List<OptionView> options) {}
    public record Chosen(boolean success, String resultText, List<EventEffectService.EffectView> effects, GameResponses.State game) {}

    private final GameRepository games;
    private final TurnRepository turns;
    private final EventDefinitionValidator validator;
    private final EventEffectService effects;
    private final ObjectMapper json;

    public TurningPointService(GameRepository games, TurnRepository turns, EventDefinitionValidator validator,
            EventEffectService effects, ObjectMapper json) {
        this.games = games;
        this.turns = turns;
        this.validator = validator;
        this.effects = effects;
        this.json = json;
    }

    public Pending pending(long gameId) {
        var rows = games.jdbc().query("""
                SELECT id, turn, payload::text FROM game_turning_points WHERE game_id = ? AND resolved_at IS NULL
                """, (row, index) -> new Pending(row.getLong("id"), row.getInt("turn"),
                json.readValue(row.getString("payload"), FrozenPoint.class)), gameId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public View view(long gameId, long userId) {
        if (!games.game(gameId, userId, false).status().equals("PLAYING")) {
            return null;
        }
        Pending pending = pending(gameId);
        if (pending == null) {
            return null;
        }
        FrozenPoint point = pending.payload();
        return new View(pending.id(), point.code(), point.title(), point.description(), point.options().stream()
                .map(option -> new OptionView(option.optionId(), option.label(), option.successRatePct(), option.onSuccess(), option.onFail())).toList());
    }

    @Transactional
    public Chosen choose(long gameId, long userId, String key, long optionId) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw GameException.invalid("Idempotency-Key가 필요합니다.");
        }
        Game game = games.game(gameId, userId, true);
        Chosen cached = turns.cached(gameId, key, "turning-point-choice", Chosen.class);
        if (cached != null) {
            return cached;
        }
        GameService.playing(game);
        Pending pending = pending(gameId);
        if (pending == null) {
            boolean resolved = Boolean.TRUE.equals(games.jdbc().queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM game_turning_points WHERE game_id = ?)", Boolean.class, gameId));
            throw new GameException(resolved ? 409 : 404, resolved ? "TURNING_POINT_ALREADY_RESOLVED" : "NO_PENDING_TURNING_POINT", "대기 중인 전환점이 없습니다.");
        }
        FrozenOption option = pending.payload().options().stream().filter(item -> item.optionId() == optionId).findFirst()
                .orElseThrow(() -> GameException.invalid("이 전환점의 선택지가 아닙니다."));
        boolean success = EventRandom.stream(game.seed(), pending.turn(), EventRandom.Purpose.TURNING_POINT_SUCCESS,
                pending.payload().code() + "#" + option.orderNo()).nextDouble() < option.probability();
        JsonNode applied = success ? option.successEffects() : option.failEffects();
        var parsed = validator.effects(applied, false, "snapshot.effects");
        var appliedViews = effects.apply(gameId, pending.turn(), "TURNING_POINT", pending.id(), parsed);
        String text = success ? option.successText() : option.failText();
        games.jdbc().update("""
                UPDATE game_turning_points SET chosen_option_id = ?, success = ?, applied_effects = ?::jsonb,
                    result_text = ?, resolved_at = now() WHERE id = ? AND resolved_at IS NULL
                """, optionId, success, json.writeValueAsString(applied), text, pending.id());
        games.jdbc().update("UPDATE games SET version = version + 1 WHERE id = ?", gameId);
        Chosen response = new Chosen(success, text, appliedViews, games.state(gameId, userId));
        return turns.cache(gameId, key, "turning-point-choice", response, Chosen.class);
    }
}
