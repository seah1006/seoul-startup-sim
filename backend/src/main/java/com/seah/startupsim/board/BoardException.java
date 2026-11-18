package com.seah.startupsim.board;

import org.springframework.http.HttpStatus;

public class BoardException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public BoardException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() { return status; }
    String code() { return code; }

    static BoardException unauthorized() {
        return new BoardException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "게스트 인증이 필요합니다.");
    }

    static BoardException invalid() {
        return invalid("요청 값을 확인해 주세요.");
    }

    static BoardException invalid(String message) {
        return new BoardException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    static BoardException notFound() {
        return new BoardException(HttpStatus.NOT_FOUND, "NOT_FOUND", "게시글을 찾을 수 없습니다.");
    }

    static BoardException forbidden() {
        return new BoardException(HttpStatus.FORBIDDEN, "FORBIDDEN", "작성자만 변경할 수 있습니다.");
    }

    static BoardException versionConflict() {
        return new BoardException(HttpStatus.CONFLICT, "POST_VERSION_CONFLICT", "게시글이 변경되었습니다. 다시 조회해 주세요.");
    }
}
