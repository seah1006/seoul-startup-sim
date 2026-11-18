package com.seah.startupsim.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 두 헤더를 지원하되 Authorization을 우선하며 비밀 값은 기록하지 않는다. */
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {
    private final AuthService service;

    public ApiTokenAuthenticationFilter(AuthService service) {
        this.service = service;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String token = request.getHeader("X-Guest-Token");
        if (authorization != null) {
            token = authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        }
        if (token != null) {
            try {
                var user = service.authenticate(token);
                var authentication = new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (AuthException exception) {
                error(response, exception.status, exception.code, exception.getMessage());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"timestamp\":\"" + Instant.now() + "\"}");
    }
}
