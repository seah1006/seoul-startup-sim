package com.seah.startupsim.catalog;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증 없이 업종, 상권 리포트, 실제 점포 조회를 공개한다. */
@RestController
public class CatalogController {
    private final CatalogService service;
    public CatalogController(CatalogService service) { this.service = service; }
    @GetMapping("/api/industries")
    public List<Map<String,Object>> industries(@RequestParam(required=false) Boolean playable) { return service.industries(playable); }
    @GetMapping("/api/areas/{code}/report")
    public Map<String,Object> report(@PathVariable String code, @RequestParam(required=false) String quarter) { return service.report(code, quarter); }
    @GetMapping("/api/areas/{code}/stores")
    public Map<String,Object> stores(@PathVariable String code, @RequestParam(required=false) String serviceCode,
            @RequestParam double lng, @RequestParam double lat, @RequestParam(defaultValue="300") int radius) {
        return service.stores(code, serviceCode, lng, lat, radius);
    }
}

