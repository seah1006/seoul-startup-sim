package com.seah.startupsim.ingest;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고정·활성·적재 중 버전과 현재 상권/점포 참조는 보존한다. */
@Service
public class DataVersionPurgeService {
    private static final String ELIGIBLE = """
            v.status IN ('READY','FAILED')
            AND NOT EXISTS (SELECT 1 FROM game_data_pins p WHERE p.version_id=v.id)
            AND NOT EXISTS (SELECT 1 FROM trade_areas a WHERE a.version_id=v.id)
            AND NOT EXISTS (SELECT 1 FROM stores s WHERE s.version_id=v.id)
            """;
    private final JdbcTemplate jdbc;
    public DataVersionPurgeService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Candidate(long id, String source, String period, String status, Long rowCount) {}
    public List<Candidate> preview() {
        return jdbc.query("SELECT v.* FROM data_versions v WHERE " + ELIGIBLE + " ORDER BY v.id",
                (rs,n) -> new Candidate(rs.getLong("id"),rs.getString("source"),rs.getString("period"),
                        rs.getString("status"),rs.getObject("row_count",Long.class)));
    }
    @Transactional
    public int purge() {
        // 활성화와 새 pin의 FK 잠금에 직렬화. 미리보기는 삭제 권한이 아니다.
        List<Long> ids = jdbc.queryForList("SELECT v.id FROM data_versions v WHERE " + ELIGIBLE
                + " ORDER BY v.id FOR UPDATE", Long.class);
        for (Long id : ids) {
            for (String table : List.of("area_sales_q", "area_stores_q", "area_floating_pop_q", "area_worker_pop_q")) {
                jdbc.update("DELETE FROM " + table + " WHERE version_id=?", id);
            }
            jdbc.update("DELETE FROM data_versions v WHERE v.id=? AND " + ELIGIBLE,id);
        }
        return ids.size();
    }
}
