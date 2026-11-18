package com.seah.startupsim.board;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;

class BoardRateLimiterTest {
    @Test void slidingWindowRejectsBoundaryBurstAndPurgesIdleIdentities() {
        var time = new AtomicLong();
        var limiter = new BoardRateLimiter(3, 10, 5, 10, 30, 15, false, time::get);
        time.set(TimeUnit.SECONDS.toNanos(59));
        for (int i = 0; i < 3; i++) limiter.acquire(BoardRateLimiter.Kind.POST, 1);
        time.set(TimeUnit.SECONDS.toNanos(60));
        assertThrows(BoardException.class, () -> limiter.acquire(BoardRateLimiter.Kind.POST, 1));
        limiter.acquire(BoardRateLimiter.Kind.POST, 2);
        time.set(TimeUnit.SECONDS.toNanos(120));
        limiter.acquire(BoardRateLimiter.Kind.POST, 3);
        assertEquals(1, limiter.trackedIdentities());
    }

    @Test void ipLimitSurvivesUserRotationAndIgnoresUntrustedForwarding() {
        var limiter = new BoardRateLimiter(3, 10, 5, 10, 30, 15, false, () -> 0L);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            for (int i = 0; i < 10; i++) {
                request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For", "192.0.2." + (i + 2));
                limiter.acquire(BoardRateLimiter.Kind.POST, i);
            }
            assertThrows(BoardException.class, () -> limiter.acquire(BoardRateLimiter.Kind.POST, 11));
            request.setRemoteAddr("192.0.2.99");
            limiter.acquire(BoardRateLimiter.Kind.POST, 11);
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }

    @Test void forwardingIsUsedOnlyWhenExplicitlyTrusted() {
        var limiter = new BoardRateLimiter(3, 10, 5, 1, 30, 15, true, () -> 0L);
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            request.addHeader("X-Forwarded-For", "192.0.2.1, 10.0.0.1");
            limiter.acquire(BoardRateLimiter.Kind.POST, 1);
            assertThrows(BoardException.class, () -> limiter.acquire(BoardRateLimiter.Kind.POST, 2));
            request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For", "192.0.2.2");
            limiter.acquire(BoardRateLimiter.Kind.POST, 2);
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
}
