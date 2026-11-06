package com.seah.startupsim.area;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

/** 지도 상권 조회 API다. */
@RestController
public class AreaController {
    private final AreaService service;
    public AreaController(AreaService service) { this.service = service; }
    @GetMapping("/api/areas")
    public Map<String, Object> areas(@RequestParam String bbox, @RequestParam(defaultValue = "15") int zoom) { return service.areas(bbox, zoom); }
    @GetMapping("/api/areas/search")
    public List<Map<String, Object>> search(@RequestParam String q) { return service.search(q); }
    @GetMapping("/api/areas/{code}")
    public Map<String, Object> detail(@PathVariable String code) { return service.detail(code); }
}
