package com.seah.startupsim.game;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
/** 검토 전 manual 매핑은 쓰지 않고 초안의 exact 항목만 사용한다. */
@Component
public class ExactIndustryMapping implements IndustryMappingPort {
    private final List<String> rows;
    public ExactIndustryMapping() throws IOException {
        try(var stream = new ClassPathResource("game/exact-industry-mapping.csv").getInputStream()) {
            rows = new String(stream.readAllBytes(),StandardCharsets.UTF_8).lines().toList();
        }
    }
    public List<String> smallCodes(String code) {
        return rows.stream().map(r->r.split(",")).filter(r->r.length==2 && r[1].equals(code)).map(r->r[0]).toList();
    }
}
