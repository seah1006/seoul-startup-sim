package com.seah.startupsim.board;

import com.seah.startupsim.game.GameException;
import com.seah.startupsim.game.GuestAuth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class BoardGuestAuth {
    static final String USER_ID = "boardUserId";
    private final GuestAuth guestAuth;

    public BoardGuestAuth(GuestAuth guestAuth) {
        this.guestAuth = guestAuth;
    }

    public long required(String token) {
        try {
            return guestAuth.resolve(token);
        } catch (GameException e) {
            throw BoardException.unauthorized();
        }
    }

    public Long optional(String token) {
        return token == null ? null : required(token);
    }

    static boolean isRead(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    }

    void bind(HttpServletRequest request, String token, boolean optional) {
        Long userId = optional ? optional(token) : Long.valueOf(required(token));
        if (userId != null) request.setAttribute(USER_ID, userId);
    }
}
