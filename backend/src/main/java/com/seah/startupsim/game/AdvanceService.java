package com.seah.startupsim.game;

import com.seah.startupsim.game.GameResponses.Advanced;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 행 잠금은 동시 쓰기를, 버전은 오래된 화면을, 멱등 키는 통신 재시도의 중복 결산을 막는다. */
@Service
public class AdvanceService {
    private final GameRepository games;
    private final QuarterCalculator calculator;
    private final TurnEventHook eventHook;
    private final ApplicationEventPublisher events;
    private final TurnRepository turns;
    private final LedgerRepository ledger;
    private final MarketDataRepository marketData;
    private final GameModifierRepository modifiers;

    public AdvanceService(GameRepository games, QuarterCalculator calculator, TurnEventHook eventHook,
            ApplicationEventPublisher events, TurnRepository turns, LedgerRepository ledger,
            MarketDataRepository marketData, GameModifierRepository modifiers) {
        this.games = games;
        this.calculator = calculator;
        this.eventHook = eventHook;
        this.events = events;
        this.turns = turns;
        this.ledger = ledger;
        this.marketData = marketData;
        this.modifiers = modifiers;
    }

    /** 결산·원장·종료·이벤트 표현·멱등 응답은 실패 시 함께 롤백해야 한다. */
    @Transactional
    public Advanced advance(long id, long user, String key, long expectedVersion) {
        validateKey(key);
        Game game = games.game(id, user, true);
        Advanced cached = turns.cached(id, key);
        if (cached != null) {
            return cached;
        }
        validateGame(game, expectedVersion);
        QuarterSnapshot snapshot = loadSnapshot(game);
        QuarterOutcome outcome = calculator.calculate(snapshot.input()).withAdditionalDataNotes(snapshot.dataNotes());
        saveSettlement(game, snapshot, outcome);
        String ending = finishQuarter(game, outcome);
        TurnResultBuilder result = applyEvents(game, outcome, ending);
        Advanced response = new Advanced(result.build(),
                games.state(id, user).withPendingTurningPoint(result.pendingTurningPoint()));
        return turns.cache(id, key, response);
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw GameException.invalid("Idempotency-Key가 필요합니다.");
        }
    }

    private void validateGame(Game game, long expectedVersion) {
        if (game.version() != expectedVersion) {
            throw new GameException(409, "GAME_VERSION_CONFLICT", "게임 버전이 변경되었습니다.");
        }
        GameService.playing(game);
    }

    private QuarterSnapshot loadSnapshot(Game game) {
        var decision = turns.decision(game.id(), game.currentTurn());
        Shop shop = games.shop(game.id());
        if (shop == null) {
            throw GameException.invalid("먼저 창업해 주세요.");
        }
        QuarterSnapshot snapshot = marketData.snapshot(game, shop, games.owner(game.id()), decision);
        return new QuarterSnapshot(snapshot.input().withModifiers(modifiers.active(game.id(), game.currentTurn())),
                snapshot.dataNotes());
    }

    private void saveSettlement(Game game, QuarterSnapshot snapshot, QuarterOutcome outcome) {
        turns.saveOutcome(game.id(), snapshot.marketSize(), outcome);
        ledger.recordQuarter(game.id(), outcome);
        games.applyStats(game.id(), outcome.statChanges());
    }

    private String finishQuarter(Game game, QuarterOutcome outcome) {
        int negatives = outcome.cashAfter() < 0 ? game.negativeQuarters() + 1 : 0;
        String ending = negatives >= 2 ? "CLOSED" : outcome.quarter().equals(game.lastQuarter()) ? "LONG_RUNNING" : null;
        games.finishQuarter(game.id(), outcome.cashAfter(), negatives, ending);
        return ending;
    }

    private TurnResultBuilder applyEvents(Game game, QuarterOutcome outcome, String ending) {
        TurnResultBuilder result = new TurnResultBuilder(outcome);
        eventHook.afterQuarter(new GameContext(game.id(), game.userId(), game.seed(), outcome.turn(),
                outcome.quarter(), ending), outcome, result);
        turns.saveEventPresentation(game.id(), result.build());
        if (ending != null) {
            events.publishEvent(new GameEndedEvent(game.id(), ending));
        }
        return result;
    }
}
