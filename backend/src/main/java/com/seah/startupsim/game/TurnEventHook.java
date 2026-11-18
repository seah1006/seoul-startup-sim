package com.seah.startupsim.game;

/** 분기 난수 계산을 건드리지 않고 전환점·반응을 추가하기 위한 확장 경계. */
public interface TurnEventHook {
    void afterQuarter(GameContext ctx, QuarterOutcome outcome, TurnResultBuilder result);
}
