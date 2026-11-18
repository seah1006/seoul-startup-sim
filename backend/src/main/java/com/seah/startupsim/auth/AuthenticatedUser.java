package com.seah.startupsim.auth;

/** API 인증이 확인된 사용자의 최소 정보. */
public record AuthenticatedUser(long userId, String role, String tokenHash) {
}
