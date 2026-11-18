package com.seah.startupsim.game;

import com.seah.startupsim.common.MoneyFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 호출자의 게임 행 잠금과 트랜잭션 안에서 검증된 효과를 배열 순서대로 적용한다. */
@Service
public class EventEffectService {
    public record EffectView(String label, String value) {}
    private static final Map<String, String> STAT_NAMES = Map.of("cooking", "요리", "service", "서비스",
            "management", "경영", "marketing", "마케팅", "stamina", "체력", "network", "인맥");
    private final JdbcTemplate jdbc;

    public EventEffectService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EffectView> apply(long gameId, int turn, String source, Long sourceRef, List<EventDsl.Effect> effects) {
        for (EventDsl.Effect effect : effects) {
            switch (effect) {
                case EventDsl.Cash cash -> {
                    jdbc.update("UPDATE games SET cash = cash + ? WHERE id = ?", cash.amount(), gameId);
                    jdbc.update("""
                            INSERT INTO ledger_entries (game_id, turn, amount, type, memo)
                            VALUES (?, ?, ?, 'EVENT', ?)
                            """, gameId, turn, cash.amount(), cash.memo());
                }
                case EventDsl.Stat stat -> {
                    if (!OwnerCandidates.STATS.contains(stat.stat())) {
                        throw new IllegalArgumentException("알 수 없는 능력치입니다.");
                    }
                    jdbc.update("""
                            UPDATE owners SET %s = greatest(0, least(100, %s + ?)) WHERE game_id = ?
                            """.formatted(stat.stat(), stat.stat()), stat.delta(), gameId);
                }
                case EventDsl.ModifierEffect modifier -> jdbc.update("""
                        INSERT INTO game_modifiers
                            (game_id, source, source_ref, target, mode, value, label, start_turn, end_turn)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, gameId, source, sourceRef, modifier.target(), modifier.mode(), modifier.value(),
                        modifier.label(), turn + 1, turn + 1 + modifier.quarters());
                case EventDsl.Flag flag -> {
                    if (flag.value()) {
                        jdbc.update("""
                                INSERT INTO game_flags (game_id, flag, set_turn) VALUES (?, ?, ?)
                                ON CONFLICT (game_id, flag) DO UPDATE SET set_turn = excluded.set_turn
                                """, gameId, flag.flag(), turn);
                    } else {
                        jdbc.update("DELETE FROM game_flags WHERE game_id = ? AND flag = ?", gameId, flag.flag());
                    }
                }
                case EventDsl.ShopCount count -> jdbc.update("""
                        UPDATE games SET shop_count = greatest(1, least(5, shop_count + ?)) WHERE id = ?
                        """, count.delta(), gameId);
                case EventDsl.RivalAdd rival -> {
                    for (int index = 0; index < rival.count(); index++) {
                        jdbc.update("""
                                INSERT INTO rivals (game_id, is_virtual, service_code, geom, is_franchise, opened_turn)
                                SELECT game_id, true, service_code, ST_Translate(geom, ?, 0), ?, ?
                                FROM shops WHERE game_id = ?
                                """, (index + 1) * 0.0001, rival.franchise(), turn + 1, gameId);
                    }
                }
                case EventDsl.Text ignored -> {}
            }
        }
        return describe(effects);
    }

    /** 화면용 요약이다. 내부 상태인 플래그는 노출하지 않는다. */
    public List<EffectView> describe(List<EventDsl.Effect> effects) {
        return effects.stream().filter(effect -> !(effect instanceof EventDsl.Flag)).map(effect -> switch (effect) {
            case EventDsl.Cash cash -> new EffectView(cash.memo(), MoneyFormat.signedWon(cash.amount()));
            case EventDsl.Stat stat -> new EffectView(STAT_NAMES.get(stat.stat()), "%+d".formatted(stat.delta()));
            case EventDsl.ModifierEffect modifier -> new EffectView(modifier.label(), modifierValue(modifier));
            case EventDsl.Flag flag -> throw new IllegalStateException("플래그는 요약에서 제외됩니다.");
            case EventDsl.ShopCount count -> new EffectView("매장 수", "%+d".formatted(count.delta()));
            case EventDsl.RivalAdd rival -> new EffectView("같은 업종 가게", "+%d곳".formatted(rival.count()));
            case EventDsl.Text text -> new EffectView(text.text(), "");
        }).toList();
    }

    /**
     * 선택 전 화면의 효과 요약 문장. 보정 라벨에는 이미 수치가 들어 있으므로 라벨만 쓰고,
     * 현금은 화면 금액 규칙({@link MoneyFormat})으로 "-300만 원"처럼 쓴다.
     * 선택 결과의 {@link #describe} 값도 같은 금액 규칙을 쓴다.
     */
    public String summary(List<EventDsl.Effect> effects) {
        List<String> parts = effects.stream().filter(effect -> !(effect instanceof EventDsl.Flag))
                .map(effect -> switch (effect) {
                    case EventDsl.ModifierEffect modifier -> modifier.label();
                    case EventDsl.Cash cash -> cash.memo() + " " + MoneyFormat.signedWon(cash.amount());
                    default -> join(describe(List.of(effect)).getFirst());
                })
                .toList();
        return parts.isEmpty() ? "변화 없음" : String.join(", ", parts);
    }

    private static String join(EffectView view) {
        return view.value().isEmpty() ? view.label() : view.label() + " " + view.value();
    }

    private static String modifierValue(EventDsl.ModifierEffect modifier) {
        if (modifier.mode().equals("MULTIPLY")) {
            return "%+d%%".formatted(Math.round((modifier.value() - 1) * 100));
        }
        return switch (modifier.target()) {
            case "costRate" -> "%+d%%p".formatted(Math.round(modifier.value() * 100));
            case "rent", "revenue" -> MoneyFormat.signedWon(Math.round(modifier.value()));
            default -> "%+,d명".formatted(Math.round(modifier.value()));
        };
    }
}
