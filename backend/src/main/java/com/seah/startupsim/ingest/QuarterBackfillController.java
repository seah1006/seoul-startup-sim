package com.seah.startupsim.ingest;


import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** 관리자 인증과 CSRF 검증으로 보호되는 분기 백필 API다. */
@RestController

public class QuarterBackfillController {
    private final QuarterBackfillService service;

    public QuarterBackfillController(QuarterBackfillService service) {
        this.service = service;
    }

    @PostMapping("/admin/api/backfill/{source}")
    public List<QuarterBackfillService.Result> backfill(@PathVariable String source,
            @RequestParam String from, @RequestParam String to) {
        return service.backfill(source, from, to);
    }
}


