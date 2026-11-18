package com.seah.startupsim.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 인증 구현과 독립적인 단일 JVM 슬라이딩 윈도우. 신뢰 프록시에서만 전달 IP를 사용한다. */
@Component
@Order(1)
public class IpRateLimitFilter extends OncePerRequestFilter {
    private final Set<String> paths;
    private final int limit;
    private final long windowMillis;
    private final boolean trustProxy;
    private final int maxIps;
    private final Map<String,ArrayDeque<Long>> requests=new HashMap<>();
    public IpRateLimitFilter(
            @Value("${app.security.ip-rate-limit.paths:/api/guest,/api/auth/signup,/api/auth/login}") String paths,
            @Value("${app.security.ip-rate-limit.max-requests:20}") int limit,
            @Value("${app.security.ip-rate-limit.window-seconds:600}") long seconds,
            @Value("${app.security.trust-proxy:false}") boolean trustProxy,
            @Value("${app.security.ip-rate-limit.max-ips:10000}") int maxIps) {
        this.paths=java.util.Arrays.stream(paths.split(",")).map(String::trim).filter(s->!s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if(limit<1 || seconds<1 || seconds>86400 || maxIps<1) throw new IllegalArgumentException("IP 제한 설정 오류");
        this.limit=limit;this.windowMillis=seconds*1000;this.trustProxy=trustProxy;this.maxIps=maxIps;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !paths.contains(request.getServletPath().isEmpty()?request.getRequestURI():request.getServletPath());
    }
    private synchronized boolean allow(String ip,long now) {
        long cutoff=now-windowMillis;
        requests.values().forEach(q->{while(!q.isEmpty() && q.peekFirst()<=cutoff)q.removeFirst();});
        requests.entrySet().removeIf(e->e.getValue().isEmpty());
        var queue=requests.get(ip);
        if(queue==null) {
            if(requests.size()>=maxIps)return false;
            queue=new ArrayDeque<>();requests.put(ip,queue);
        }
        if(queue.size()>=limit)return false;
        queue.addLast(now);return true;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String ip=request.getRemoteAddr();
        if(trustProxy) {
            String forwarded=request.getHeader("X-Forwarded-For");
            if(forwarded!=null && !forwarded.isBlank())ip=forwarded.split(",",2)[0].trim();
        }
        if(allow(ip,System.currentTimeMillis())) {chain.doFilter(request,response);return;}
        response.setStatus(429);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After",Long.toString((windowMillis+999)/1000));
        response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\",\"timestamp\":\""+Instant.now()+"\"}");
    }
}
