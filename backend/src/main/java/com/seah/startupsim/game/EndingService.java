package com.seah.startupsim.game;

import com.seah.startupsim.game.GameResponses.State;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 매각은 advance와 같은 잠금·버전·멱등 규칙으로 직렬화하고, 요약은 엔딩 기록만 원본으로 쓴다. */
@Service
public class EndingService {
    private static final int AVERAGE_QUARTERS = 4;
    private static final double DEFAULT_SELL_QUARTERS = 4;
    private static final double DEFAULT_MIN_QUARTERS = 4;

    private final GameRepository games;
    private final TurnRepository turns;
    private final GameRecordRepository records;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public EndingService(GameRepository games, TurnRepository turns, GameRecordRepository records,
            ApplicationEventPublisher events, Clock clock) {
        this.games = games;
        this.turns = turns;
        this.records = records;
        this.events = events;
        this.clock = clock;
    }

    /** 현금·원장·종료 상태·엔딩 기록·멱등 응답은 실패 시 함께 롤백해야 한다. */
    @Transactional
    public State sell(long id, long user, String key, long expectedVersion) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw GameException.invalid("Idempotency-Key가 필요합니다.");
        }
        Game game = games.game(id, user, true);
        State cached = turns.cached(id, key, "sell", State.class);
        if (cached != null) {
            return cached;
        }
        if (game.version() != expectedVersion) {
            throw new GameException(409, "GAME_VERSION_CONFLICT", "게임 버전이 변경되었습니다.");
        }
        GameService.playing(game);
        if (games.pendingTurningPoint(id)) {
            throw new GameException(409, "TURNING_POINT_PENDING", "전환점을 먼저 선택해 주세요.");
        }
        List<Long> profits = games.jdbc().queryForList("""
                SELECT profit FROM turn_results WHERE game_id = ? ORDER BY turn DESC
                """, Long.class, id);
        // 설정은 게임 생성 시 스냅샷이다. V9 이전에 만든 게임에는 키가 없어 기본값을 쓴다.
        int minQuarters = (int) Math.round(game.settings().getOrDefault("sell.minQuarters", DEFAULT_MIN_QUARTERS));
        if (profits.size() < minQuarters) {
            throw GameException.invalid("영업 " + minQuarters + "분기 이후에 매각할 수 있습니다.");
        }
        long value = saleValue(profits, game.settings().getOrDefault("sell.quarters", DEFAULT_SELL_QUARTERS));
        games.jdbc().update("""
                INSERT INTO ledger_entries (game_id, turn, amount, type, memo) VALUES (?, ?, ?, 'SALE', '가게 매각')
                """, id, game.currentTurn() - 1, value);
        games.jdbc().update("""
                UPDATE games SET cash = cash + ?, status = 'ENDED', ending_type = 'SOLD', version = version + 1
                WHERE id = ?
                """, value, id);
        records.insert(id, "SOLD", value, clock.instant());
        events.publishEvent(new GameEndedEvent(id, "SOLD"));
        return turns.cache(id, key, "sell", games.state(id, user), State.class);
    }

    /** max(0, 최근 4분기 평균 순이익) × 설정 분기 수, 원 단위 반올림. */
    static long saleValue(List<Long> latestFirst, double quarters) {
        List<Long> recent = latestFirst.subList(0, Math.min(AVERAGE_QUARTERS, latestFirst.size()));
        BigDecimal sum = BigDecimal.ZERO;
        for (Long profit : recent) {
            sum = sum.add(BigDecimal.valueOf(profit));
        }
        if (recent.isEmpty() || sum.signum() <= 0) {
            return 0;
        }
        return sum.multiply(BigDecimal.valueOf(quarters))
                .divide(BigDecimal.valueOf(recent.size()), 0, RoundingMode.HALF_UP).longValueExact();
    }

    public GameRecordRepository.Summary summary(long id, long user) {
        Game game = games.game(id, user, false);
        if (!"ENDED".equals(game.status())) {
            throw new GameException(409, "GAME_NOT_ENDED", "아직 끝나지 않은 게임입니다.");
        }
        GameRecordRepository.Summary summary = records.summary(id);
        if (summary == null) {
            throw new IllegalStateException("종료된 게임의 엔딩 기록이 없습니다: " + id);
        }
        return summary;
    }
}
