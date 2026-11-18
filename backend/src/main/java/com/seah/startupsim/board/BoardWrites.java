package com.seah.startupsim.board;

import java.sql.SQLException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.http.HttpStatus;

final class BoardWrites {
    private static final Logger LOG = LoggerFactory.getLogger(BoardWrites.class);

    private BoardWrites() {}

    static <T> T safe(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataIntegrityViolationException | UncategorizedSQLException e) {
            // No current constraint maps to a public 4xx; do not log or expose DB/trigger text.
            String sqlState = e.getMostSpecificCause() instanceof SQLException sql ? sql.getSQLState() : "unknown";
            LOG.error("게시판 DB 쓰기 실패: SQLSTATE={}", sqlState);
            throw new BoardException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청 처리 중 오류가 발생했습니다.");
        }
    }
}
