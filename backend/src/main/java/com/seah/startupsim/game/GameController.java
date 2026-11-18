package com.seah.startupsim.game;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.seah.startupsim.game.GameResponses.Guest;
import com.seah.startupsim.game.GameResponses.Candidates;
import com.seah.startupsim.game.GameResponses.State;
import com.seah.startupsim.game.GameResponses.DecisionSaved;
import com.seah.startupsim.game.GameResponses.Advanced;
import java.util.List;
import com.seah.startupsim.game.GameTypes.Decision;
import com.seah.startupsim.game.GameTypes.Create;
import com.seah.startupsim.game.GameTypes.Shop;
import com.seah.startupsim.game.GameTypes.Advance;
import com.seah.startupsim.game.GameTypes.Background;

/** 게스트 인증을 해석한 뒤 게임 서비스에 요청을 전달한다. */
@RestController
@RequestMapping("/api")
public class GameController {
    private final GuestAuth auth;
    private final OwnerCandidates candidates;
    private final GameService service;
    private final AdvanceService advance;
    private final GameRepository repository;
    public GameController(GuestAuth auth,OwnerCandidates candidates,GameService service,AdvanceService advance,GameRepository repository) {
        this.auth=auth;this.candidates=candidates;this.service=service;this.advance=advance;this.repository=repository;
    }
    @PostMapping("/guest") @ResponseStatus(HttpStatus.CREATED)
    public Guest guest() { return auth.create(); }
    @GetMapping("/owners/candidates")
    public Candidates candidates(@RequestHeader(value="X-Guest-Token",required=false) String token,@RequestParam Background background,@RequestParam long seed) {
        auth.resolve(token);return new Candidates(background, candidates.generate(background,seed));
    }
    @PostMapping("/games") @ResponseStatus(HttpStatus.CREATED)
    public State create(@RequestHeader(value="X-Guest-Token",required=false) String token,@Valid @RequestBody Create request) { return service.create(auth.resolve(token),request); }
    @GetMapping("/games/{id}")
    public State state(@RequestHeader(value="X-Guest-Token",required=false) String token,@PathVariable long id) { return repository.state(id,auth.resolve(token)); }
    @GetMapping("/games/{id}/turns")
    public List<QuarterOutcome> turns(@RequestHeader(value="X-Guest-Token",required=false) String token,@PathVariable long id) { return repository.turns(id,auth.resolve(token)); }
    @PostMapping("/games/{id}/shop")
    public State shop(@RequestHeader(value="X-Guest-Token",required=false) String token,@PathVariable long id,@Valid @RequestBody Shop request) { return service.shop(id,auth.resolve(token),request); }
    @PutMapping("/games/{id}/turns/{turn}/decision")
    public DecisionSaved decision(@RequestHeader(value="X-Guest-Token",required=false) String token,@PathVariable long id,@PathVariable int turn,@Valid @RequestBody Decision request) { return service.decision(id,auth.resolve(token),turn,request); }
    @PostMapping("/games/{id}/advance")
    public Advanced advance(@RequestHeader(value="X-Guest-Token",required=false) String token,@RequestHeader(value="Idempotency-Key",required=false) String key,@PathVariable long id,@Valid @RequestBody Advance request) { return advance.advance(id,auth.resolve(token),key,request.expectedVersion()); }
}

