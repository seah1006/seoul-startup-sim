package com.seah.startupsim.game;

import java.util.List;

/** 계산기의 불변 결과에서 이벤트 표현만 바꾸는 한정된 변경 창구. */
public final class TurnResultBuilder {
    private final QuarterOutcome outcome;
    private Object moment;
    private List<Object> reactions;
    private boolean pendingTurningPoint;

    public TurnResultBuilder(QuarterOutcome outcome) {
        this.outcome = outcome;
        this.moment = outcome.moment();
        this.reactions = outcome.reactions();
    }

    public void moment(Object moment) {
        this.moment = moment;
    }

    public void reactions(List<Object> reactions) {
        this.reactions = List.copyOf(reactions);
    }

    public void pendingTurningPoint(boolean pendingTurningPoint) {
        this.pendingTurningPoint = pendingTurningPoint;
    }

    public boolean pendingTurningPoint() {
        return pendingTurningPoint;
    }

    public QuarterOutcome build() {
        return new QuarterOutcome(outcome.turn(), outcome.quarter(), outcome.headline(),
                outcome.revenue(), outcome.visitors(), outcome.avgTicket(), outcome.costs(),
                outcome.profit(), outcome.cashAfter(), outcome.areaRank(), outcome.statChanges(),
                outcome.areaNews(), moment, reactions, outcome.dataNotes(), outcome.missionEvent());
    }
}
