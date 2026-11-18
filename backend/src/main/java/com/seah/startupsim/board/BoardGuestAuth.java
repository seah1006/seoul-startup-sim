package com.seah.startupsim.board;

import com.seah.startupsim.game.GameException;
import com.seah.startupsim.game.GuestAuth;
import org.springframework.stereotype.Component;

@Component
public class BoardGuestAuth {
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
}
