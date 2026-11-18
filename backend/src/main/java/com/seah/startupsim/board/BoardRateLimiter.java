package com.seah.startupsim.board;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
class BoardRateLimiter {
    enum Kind { POST, COMMENT, REPORT }
    private record Key(Kind kind, String identity) {}
    private static final long WINDOW = TimeUnit.MINUTES.toNanos(1);
    private final Map<Kind, Integer> limits;
    private final Map<Kind, Integer> ipLimits;
    private final Map<Key, ArrayDeque<Long>> uses = new HashMap<>();
    private final boolean trustProxy;
    private final LongSupplier clock;

    @Autowired
    BoardRateLimiter(@Value("${app.board.rate-limit.posts}") int posts,
            @Value("${app.board.rate-limit.comments}") int comments,
            @Value("${app.board.rate-limit.reports}") int reports,
            @Value("${app.board.rate-limit.ip-posts:10}") int ipPosts,
            @Value("${app.board.rate-limit.ip-comments:30}") int ipComments,
            @Value("${app.board.rate-limit.ip-reports:15}") int ipReports,
            @Value("${app.board.rate-limit.trust-proxy:false}") boolean trustProxy) {
        this(posts, comments, reports, ipPosts, ipComments, ipReports, trustProxy, System::nanoTime);
    }

    BoardRateLimiter(int posts, int comments, int reports, int ipPosts, int ipComments, int ipReports,
            boolean trustProxy, LongSupplier clock) {
        if (posts < 1 || comments < 1 || reports < 1 || ipPosts < 1 || ipComments < 1 || ipReports < 1)
            throw new IllegalArgumentException("게시판 작성 한도는 양수여야 합니다.");
        limits = Map.of(Kind.POST, posts, Kind.COMMENT, comments, Kind.REPORT, reports);
        ipLimits = Map.of(Kind.POST, ipPosts, Kind.COMMENT, ipComments, Kind.REPORT, ipReports);
        this.trustProxy = trustProxy;
        this.clock = clock;
    }

    synchronized void acquire(Kind kind, long userId) {
        long now = clock.getAsLong();
        // Purge idle identities as well as the current identity on every admission.
        uses.values().removeIf(events -> {
            while (!events.isEmpty() && now - events.peekFirst() >= WINDOW) events.removeFirst();
            return events.isEmpty();
        });
        Key user = new Key(kind, "user:" + userId);
        String ip = clientIp();
        Key client = ip == null ? null : new Key(kind, "ip:" + ip);
        check(user, limits.get(kind));
        if (client != null) check(client, ipLimits.get(kind));
        uses.computeIfAbsent(user, ignored -> new ArrayDeque<>()).addLast(now);
        if (client != null) uses.computeIfAbsent(client, ignored -> new ArrayDeque<>()).addLast(now);
    }

    private String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        var request = attributes.getRequest();
        if (trustProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",", 2)[0].strip();
                // Accept only literal IP syntax, never arbitrary user-controlled identity strings.
                if (first.length() <= 45 && first.matches("[0-9a-fA-F:.]+")) return first;
            }
        }
        return request.getRemoteAddr();
    }

    private void check(Key key, int limit) {
        if (uses.containsKey(key) && uses.get(key).size() >= limit)
            throw new BoardException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "작성 횟수가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
    }

    synchronized int trackedIdentities() { return uses.size(); }
}
