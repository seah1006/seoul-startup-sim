package com.seah.startupsim.ingest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** 명시적으로 켰을 때만 등록되는 로컬 검증용 관리자 API다. */
@RestController
@ConditionalOnProperty(name = "app.admin.enabled", havingValue = "true")
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
