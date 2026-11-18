package com.seah.startupsim.game;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

/** 매출 난수를 소비하지 않고 목적과 정의 코드별로 독립된 난수 스트림을 만든다. */
public final class EventRandom {
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private EventRandom() {}

    public enum Purpose {
        TURNING_POINT_FIRE, TURNING_POINT_PICK, TURNING_POINT_SUCCESS,
        REACTION_PICK, REACTION_LIKES, MOMENT_PICK, MOMENT_JUDGE,
        VIRTUAL_RIVAL, RIVAL_CLOSURE
    }

    /** UTF-8 바이트를 FNV-1a 64비트로 해시해 JVM과 삽입 순서에 의존하지 않는다. */
    static long hash64(String text) {
        long hash = 0xCBF29CE484222325L;
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (value & 0xFF)) * 0x100000001B3L;
        }
        return hash;
    }

    /** SplitMix64 최종 혼합으로 인접한 입력의 상관을 제거한다. */
    static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /** 같은 입력은 언제나 같은 첫 값을 내며 다른 정의의 판정에 영향을 주지 않는다. */
    public static SplittableRandom stream(long seed, int turn, Purpose purpose, String code) {
        return new SplittableRandom(streamSeed(seed, turn, purpose, code));
    }

    static long streamSeed(long seed, int turn, Purpose purpose, String code) {
        return mix64(seed ^ ((long) turn * GOLDEN) ^ hash64(purpose.name() + '|' + code));
    }
}
