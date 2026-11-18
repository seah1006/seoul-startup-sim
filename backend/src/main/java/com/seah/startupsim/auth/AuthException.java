package com.seah.startupsim.auth;

/** 비밀 값을 포함하지 않는 인증 도메인 오류. */
public class AuthException extends RuntimeException {
    public final int status;
    public final String code;

    public AuthException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AuthException unauthorized() {
        return new AuthException(401, "UNAUTHORIZED", "인증이 필요합니다.");
    }

    public static AuthException credentials() {
        return new AuthException(401, "INVALID_CREDENTIALS", "아이디 또는 비밀번호를 확인해 주세요.");
    }
}
