package com.seah.startupsim.game;

/** 계약 오류 코드와 상태를 보존해 공통 예외 처리기가 같은 응답을 만들게 한다. */
public class GameException extends RuntimeException {
    final int status;
    final String code;

    public GameException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static GameException invalid(String message) {
        return new GameException(400, "VALIDATION_FAILED", message);
    }
}
