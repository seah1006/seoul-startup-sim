package com.seah.startupsim.ranking;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증 없이 공개되는 일간 랭킹 조회. 잘못된 값은 공통 처리기가 400 VALIDATION_FAILED로 바꾼다. */
@RestController
public class RankingController {
    private final RankingService service;

    public RankingController(RankingService service) {
        this.service = service;
    }

    @GetMapping("/api/rankings")
    public RankingService.Ranking rankings(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) RankingKind kind,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) Integer limit) {
        return service.ranking(date, kind, key, limit);
    }
}
