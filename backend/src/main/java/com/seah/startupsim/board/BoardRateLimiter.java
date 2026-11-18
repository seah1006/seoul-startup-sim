package com.seah.startupsim.board;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class BoardRateLimiter {
    enum Kind { POST, COMMENT, REPORT }
    private final Map<Kind, Integer> limits;
    private final Map<Kind, Map<Long, Integer>> counters = new EnumMap<>(Kind.class);
    private final long start = System.nanoTime();
    private long window;

    BoardRateLimiter(@Value("${app.board.rate-limit.posts}") int posts,
            @Value("${app.board.rate-limit.comments}") int comments,
            @Value("${app.board.rate-limit.reports}") int reports) {
        if (posts < 1 || comments < 1 || reports < 1) throw new IllegalArgumentException("게시판 작성 한도는 양수여야 합니다.");
        limits = Map.of(Kind.POST, posts, Kind.COMMENT, comments, Kind.REPORT, reports);
    }

    synchronized void acquire(Kind kind, long userId) {
        long current = TimeUnit.NANOSECONDS.toMinutes(System.nanoTime() - start);
        if (current > window) {
            counters.clear();
            window = current;
        }
        var uses = counters.computeIfAbsent(kind, ignored -> new HashMap<>());
        int used = uses.getOrDefault(userId, 0);
        if (used >= limits.get(kind)) {
            throw new BoardException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "작성 횟수가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        uses.put(userId, used + 1);
    }
}
