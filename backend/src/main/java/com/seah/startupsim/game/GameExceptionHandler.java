package com.seah.startupsim.game;

import com.seah.startupsim.common.ApiExceptionHandler.ApiError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import java.time.Instant;

/** 게임 컨트롤러에만 적용되는 계약 오류 매핑. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages="com.seah.startupsim.game")
public class GameExceptionHandler {
    @ExceptionHandler(GameException.class)
    ResponseEntity<ApiError> game(GameException e) {
        return ResponseEntity.status(e.status).body(new ApiError(e.code,e.getMessage(),Instant.now()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, MissingRequestHeaderException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> invalid(Exception e) {
        return game(GameException.invalid("요청 값을 확인해 주세요."));
    }
}
