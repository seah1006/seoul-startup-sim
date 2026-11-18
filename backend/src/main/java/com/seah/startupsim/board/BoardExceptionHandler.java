package com.seah.startupsim.board;

import com.seah.startupsim.common.ApiExceptionHandler.ApiError;
import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {BoardPostController.class, BoardCommentController.class})
public class BoardExceptionHandler {
    @ExceptionHandler(BoardException.class)
    ResponseEntity<ApiError> handle(BoardException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage(), Instant.now()));
    }
}
