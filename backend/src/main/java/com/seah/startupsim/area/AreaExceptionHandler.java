package com.seah.startupsim.area;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** 상권 조회의 잘못된 조건과 존재하지 않는 코드를 공통 내부 오류와 구분한다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AreaController.class)
public class AreaExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "INVALID_AREA_QUERY", "message", e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> missing(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("code", "AREA_NOT_FOUND", "message", "상권을 찾을 수 없습니다."));
    }
}
