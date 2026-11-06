package com.seah.startupsim.game;

/** 계약 오류 코드와 상태를 보존하는 게임 예외. */
public class GameException extends RuntimeException {
    final int status;
    final String code;
    public GameException(int status, String code, String message) {
        super(message); this.status=status; this.code=code;
    }
    public static GameException invalid(String message) { return new GameException(400,"VALIDATION_FAILED",message); }
}
