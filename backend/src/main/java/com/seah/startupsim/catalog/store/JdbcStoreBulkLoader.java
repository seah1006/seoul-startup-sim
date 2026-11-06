package com.seah.startupsim.catalog.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;

/** JDBC 배치로 문자열 키 기준 upsert한다. */
@Component
public class JdbcStoreBulkLoader implements StoreBulkLoader {
    static final String UPSERT = """
            INSERT INTO stores VALUES (?,?,?,?,?,?,?,?,?,?,?,
                ST_SetSRID(ST_MakePoint(?,?),4326),?,?)
            ON CONFLICT(store_id) DO UPDATE SET name=excluded.name,branch_name=excluded.branch_name,
            large_code=excluded.large_code,middle_code=excluded.middle_code,small_code=excluded.small_code,
            small_name=excluded.small_name,std_industry_code=excluded.std_industry_code,
            district_code=excluded.district_code,dong_code=excluded.dong_code,
            road_address=excluded.road_address,geom=excluded.geom,snapshot=excluded.snapshot,
            version_id=excluded.version_id
            """;
    private final JdbcTemplate jdbc;
    public JdbcStoreBulkLoader(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void load(List<StoreRow> rows, String snapshot, Long versionId) {
        jdbc.batchUpdate(UPSERT, rows.stream().map(r -> r.parameters(snapshot, versionId)).toList());
    }
}
