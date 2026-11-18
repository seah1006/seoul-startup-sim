package com.seah.startupsim.game;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 기존 게임 API와 같은 인증 위임으로 전환점 조회·선택만 제공한다. */
@RestController
@RequestMapping("/api/games/{id}/turning-point")
public class TurningPointController {
    public record Choice(@Min(1) long optionId) {}
    private final GuestAuth auth;
    private final TurningPointService service;

    public TurningPointController(GuestAuth auth, TurningPointService service) {
        this.auth = auth;
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<TurningPointService.View> get(@PathVariable long id,
            @RequestHeader(value = "X-Guest-Token", required = false) String token) {
        var point = service.view(id, auth.resolve(token));
        return point == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(point);
    }

    @PostMapping("/choice")
    public TurningPointService.Chosen choose(@PathVariable long id,
            @RequestHeader(value = "X-Guest-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Choice request) {
        return service.choose(id, auth.resolve(token), key, request.optionId());
    }
}
