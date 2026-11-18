package com.seah.startupsim.auth;

import com.seah.startupsim.common.ApiExceptionHandler.ApiError;
import java.time.Instant;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 인증 오류를 공통 JSON 계약으로 변환한다. */
@Order(-100)
@RestControllerAdvice(basePackages = "com.seah.startupsim.auth")
public class AuthExceptionHandler {
    @ExceptionHandler(AuthException.class)
    ResponseEntity<ApiError> auth(AuthException exception) {
        return ResponseEntity.status(exception.status)
                .body(new ApiError(exception.code, exception.getMessage(), Instant.now()));
    }
}
