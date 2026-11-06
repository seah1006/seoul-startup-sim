package com.seah.startupsim.ingest;


import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** 보안 설정으로 보호되는 관리자 수집 실행 경로를 제공한다. */
@RestController

public class IngestController {
    private final IngestService service;
    public IngestController(IngestService service) { this.service = service; }
    @PostMapping("/admin/api/ingest/{source}")
    public Map<String, Long> run(@PathVariable String source, @RequestParam(required = false) String period) {
        return Map.of("run_id", service.run(source, period));
    }
}


