package com.seah.startupsim.game;

import java.util.Map;

/** 시작 시 설정 스냅샷을 유지해 진행 중 밸런스 변경으로 결과가 달라지지 않게 한다. */
public record Game(long id, long userId, long seed, String startQuarter, String lastQuarter,
        int currentTurn, String status, String endingType, long version, long cash,
        long initialCapital, int negativeQuarters, Map<String, Double> settings) {}
