package com.seah.startupsim.game;

import com.seah.startupsim.auth.AuthException;
import com.seah.startupsim.auth.AuthService;
import com.seah.startupsim.auth.AuthenticatedUser;
import com.seah.startupsim.game.GameResponses.Guest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 기존 게임 인증 창구를 공통 세션 인증으로 위임한다. */
@Component
public class GuestAuth {
    private final AuthService service;

    public GuestAuth(AuthService service) {
        this.service = service;
    }

    public Guest create() {
        return service.createGuest();
    }

    public long resolve(String token) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.userId();
        }
        try {
            return service.authenticate(token).userId();
        } catch (AuthException exception) {
            throw new GameException(exception.status, exception.code, exception.getMessage());
        }
    }
}

