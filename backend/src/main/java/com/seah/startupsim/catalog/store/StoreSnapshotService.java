package com.seah.startupsim.catalog.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Path;

/** 이전 상태를 보존한 채 전체 스냅샷을 원자적으로 교체하고 SQL 집합 연산으로 차이를 남긴다. */
@Service
public class StoreSnapshotService {
    private final JdbcTemplate jdbc;
    private final StoreCsvParser parser;
    private final CopyStoreBulkLoader loader;
    public StoreSnapshotService(JdbcTemplate jdbc, StoreCsvParser parser, CopyStoreBulkLoader loader) {
        this.jdbc = jdbc;
        this.parser = parser;
        this.loader = loader;
    }
    @Transactional
    public StoreCsvParser.Counts replace(Path file, String snapshot, Long version) {
        jdbc.execute("CREATE TEMP TABLE store_previous ON COMMIT DROP AS TABLE stores");
        jdbc.execute("TRUNCATE stores");
        StoreCsvParser.Counts counts = parser.read(file, Integer.MAX_VALUE, rows -> loader.load(rows, snapshot, version));
        jdbc.update("DELETE FROM store_snapshot_diffs WHERE snapshot=?", snapshot);
        jdbc.update("""
                INSERT INTO store_snapshot_diffs
                SELECT ?, COALESCE(n.store_id,o.store_id),
                    CASE WHEN o.store_id IS NULL THEN 'NEW'
                         WHEN n.store_id IS NULL THEN 'CLOSED' ELSE 'CHANGED' END
                FROM store_previous o FULL JOIN stores n USING(store_id)
                WHERE o.store_id IS NULL OR n.store_id IS NULL OR
                    (to_jsonb(o) - 'snapshot' - 'version_id') IS DISTINCT FROM
                    (to_jsonb(n) - 'snapshot' - 'version_id')
                """, snapshot);
        return counts;
    }
}
