package com.seah.startupsim.game;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MissionController {
    @GetMapping("/api/missions")
    public List<Mission.Definition> missions() { return Mission.definitions(); }
}
