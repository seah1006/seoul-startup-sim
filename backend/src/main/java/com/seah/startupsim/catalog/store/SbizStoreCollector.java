package com.seah.startupsim.catalog.store;

import com.seah.startupsim.ingest.CollectResult;
import com.seah.startupsim.ingest.Collector;
import com.seah.startupsim.ingest.IngestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 파일 본문 대신 파일명·크기·SHA-256·행 수만 원본 이력에 보관한다. */
@Component
public class SbizStoreCollector implements Collector {
    private final String file;
    private final StoreSnapshotService snapshots;
    private final JdbcTemplate jdbc;
    public SbizStoreCollector(@Value("${app.ingest.sbiz-csv:}") String file,
            StoreSnapshotService snapshots, JdbcTemplate jdbc) {
        this.file = file;
        this.snapshots = snapshots;
        this.jdbc = jdbc;
    }
    public String source() { return "sbiz-stores-csv"; }
    public CollectResult collect(IngestContext context) {
        if (file.isBlank() || context.targetPeriod() == null || context.targetPeriod().isBlank()) {
            throw new IllegalArgumentException("CSV 경로와 기준 시점이 필요합니다.");
        }
        Long version = jdbc.queryForObject("INSERT INTO data_versions(source,period,status) VALUES (?,?,'LOADING') RETURNING id",
                Long.class, source(), context.targetPeriod());
        try {
            Path path = Path.of(file);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) >= 0) digest.update(buffer, 0, n);
            }
            var counts = snapshots.replace(path, context.targetPeriod(), version);
            String filename = path.getFileName().toString().replace("\\", "\\\\").replace("\"", "\\\"");
            context.saveRaw("file", "{\"filename\":\"" + filename + "\",\"size\":" + Files.size(path)
                    + ",\"sha256\":\"" + HexFormat.of().formatHex(digest.digest()) + "\",\"rows\":" + counts.fetched() + "}");
            int inserted = jdbc.queryForObject("SELECT count(*) FROM store_snapshot_diffs WHERE snapshot=? AND change_type='NEW'",
                    Integer.class, context.targetPeriod());
            jdbc.update("UPDATE data_versions SET status='READY',row_count=? WHERE id=?", counts.fetched() - counts.failed(), version);
            return new CollectResult(counts.fetched(), inserted, counts.fetched() - counts.failed() - inserted, counts.failed());
        } catch (Exception e) {
            jdbc.update("UPDATE data_versions SET status='FAILED' WHERE id=?", version);
            throw new IllegalStateException("상가 CSV 수집 실패", e);
        }
    }
}
