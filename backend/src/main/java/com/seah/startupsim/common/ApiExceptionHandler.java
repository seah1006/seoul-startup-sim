package com.seah.startupsim.common;
import java.time.Instant;
import com.seah.startupsim.ingest.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
public class ApiExceptionHandler {
 @ExceptionHandler(UnknownSourceException.class)
 ResponseEntity<ApiError> unknown(UnknownSourceException e){return error(HttpStatus.BAD_REQUEST,"UNKNOWN_SOURCE",e.getMessage());}
 @ExceptionHandler(IngestAlreadyRunningException.class)
 ResponseEntity<ApiError> running(IngestAlreadyRunningException e){return error(HttpStatus.CONFLICT,"INGEST_ALREADY_RUNNING",e.getMessage());}
 @ExceptionHandler(Exception.class)
 ResponseEntity<ApiError> unexpected(Exception e){return error(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","요청 처리 중 오류가 발생했습니다.");}
 private ResponseEntity<ApiError> error(HttpStatus status,String code,String message){return ResponseEntity.status(status).body(new ApiError(code,message,Instant.now()));}
 public record ApiError(String code,String message,Instant timestamp){}
}