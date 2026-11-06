package com.seah.startupsim.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 확정된 매핑을 조회하며 NONE은 제외한다. */
@Repository
public class JdbcIndustryMapping implements IndustryMappingPort {
    private final JdbcTemplate jdbc;
    public JdbcIndustryMapping(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<String> toServiceCode(String code) {
        return jdbc.queryForList("SELECT service_code FROM industry_mappings WHERE sbiz_small_code=? AND service_code IS NOT NULL AND match_type<>'NONE'", String.class, code).stream().findFirst();
    }
    public List<String> smallCodes(String code) {
        return jdbc.queryForList("SELECT sbiz_small_code FROM industry_mappings WHERE service_code=? AND match_type<>'NONE' ORDER BY sbiz_small_code", String.class, code);
    }
}

