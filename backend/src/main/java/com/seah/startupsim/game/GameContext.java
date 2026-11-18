package com.seah.startupsim.game;

/** 종료 판정 이후에 호출되므로 후속 엔진이 종료 분기에 전환점을 만들지 않게 한다. */
public record GameContext(long gameId, long userId, long seed, int turn, String quarter,
        String endingType) {
    public boolean ended() {
        return endingType != null;
    }
}
