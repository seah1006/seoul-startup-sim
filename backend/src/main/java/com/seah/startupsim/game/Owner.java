package com.seah.startupsim.game;

import java.util.List;
import java.util.Map;

/** 이름·배경·능력치와 집중 분야를 함께 유지하는 사장님 상태. */
public record Owner(String name, String background, Map<String, Integer> stats,
        List<String> focusStats) {}
