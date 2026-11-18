package com.seah.startupsim.game;

import com.seah.startupsim.game.GameResponses.State;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 기존 게임 API와 같은 인증 위임으로 매각과 엔딩 요약만 제공한다. 게스트도 매각할 수 있다. */
@RestController
@RequestMapping("/api/games/{id}")
public class EndingController {
    public record Sell(@Min(0) long expectedVersion) {}
    private final GuestAuth auth;
    private final EndingService service;

    public EndingController(GuestAuth auth, EndingService service) {
        this.auth = auth;
        this.service = service;
    }

    @PostMapping("/sell")
    public State sell(@PathVariable long id,
            @RequestHeader(value = "X-Guest-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Sell request) {
        return service.sell(id, auth.resolve(token), key, request.expectedVersion());
    }
}
