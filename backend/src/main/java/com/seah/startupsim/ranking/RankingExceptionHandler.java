package com.seah.startupsim.ranking;

import com.seah.startupsim.common.ApiExceptionHandler.ApiError;
import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 랭킹 API에만 적용한다. 코드는 공통 계약(VALIDATION_FAILED)을 유지하고 문구만 사유별로 바꾼다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.seah.startupsim.ranking")
public class RankingExceptionHandler {
    @ExceptionHandler(RankingRequestException.class)
    ResponseEntity<ApiError> invalid(RankingRequestException exception) {
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", exception.getMessage(), Instant.now()));
    }

    /** 형식 변환 실패(날짜·종류·limit)는 어느 파라미터인지에 따라 안내한다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> mismatch(MethodArgumentTypeMismatchException exception) {
        String message = switch (exception.getName()) {
            case "date" -> RankingRequestException.DATE_FORMAT;
            case "kind" -> RankingRequestException.KIND;
            case "limit" -> RankingRequestException.LIMIT;
            default -> "요청 값을 확인해 주세요.";
        };
        return invalid(new RankingRequestException(message));
    }
}