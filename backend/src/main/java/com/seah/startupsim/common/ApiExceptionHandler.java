package com.seah.startupsim.common;

import com.seah.startupsim.ingest.IngestAlreadyRunningException;
import com.seah.startupsim.ingest.UnknownSourceException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;

/** 모든 API 오류 형식을 통일하며 예기치 않은 오류는 비밀을 가린 스택으로 기록한다. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final SecretMasker masker;

    public ApiExceptionHandler(SecretMasker masker) { this.masker = masker; }

    @ExceptionHandler(UnknownSourceException.class)
    ResponseEntity<ApiError> unknown(UnknownSourceException e) {
        return error(HttpStatus.BAD_REQUEST, "UNKNOWN_SOURCE", masker.mask(e.getMessage()));
    }

    @ExceptionHandler(IngestAlreadyRunningException.class)
    ResponseEntity<ApiError> running(IngestAlreadyRunningException e) {
        return error(HttpStatus.CONFLICT, "INGEST_ALREADY_RUNNING", masker.mask(e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, HandlerMethodValidationException.class})
    ResponseEntity<ApiError> invalid(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "요청 값을 확인해 주세요.");
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ApiError> missing(Exception e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 자료를 찾을 수 없습니다.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(Exception e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        return error(status, status == HttpStatus.NOT_FOUND ? "NOT_FOUND" : "VALIDATION_FAILED",
                status == HttpStatus.NOT_FOUND ? "요청한 자료를 찾을 수 없습니다." : "요청 값을 확인해 주세요.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        StringWriter stack = new StringWriter();
        e.printStackTrace(new PrintWriter(stack));
        log.error("예기치 않은 요청 오류: {}", masker.mask(stack.toString()));
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청 처리 중 오류가 발생했습니다.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, Instant.now()));
    }

    public record ApiError(String code, String message, Instant timestamp) {}
}
