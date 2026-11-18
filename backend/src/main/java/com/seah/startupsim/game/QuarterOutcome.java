package com.seah.startupsim.game;

import java.util.List;
import com.seah.startupsim.game.GameTypes.Costs;
import com.seah.startupsim.game.GameTypes.StatChange;

/** 계산 결과를 명시적인 계약으로 보존해 확장 훅에서도 필드 누락을 막는다. */
public record QuarterOutcome(int turn, String quarter, Headline headline, long revenue,
        long visitors, long avgTicket, Costs costs, long profit, long cashAfter,
        AreaRank areaRank, List<StatChange> statChanges, AreaNews areaNews,
        Object moment, List<Object> reactions, List<String> dataNotes) {
    public record Headline(long amount, String label) {}
    public record AreaRank(int of, int rank) {}
    public record AreaNews(long closings, long openings, String source) {}
}
