package com.seah.startupsim.ingest;

import com.seah.startupsim.ingest.client.SeoulOpenApiClient;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** 분기 인자가 없는 직장인구 응답 전체를 받아 분기별 활성 버전으로 저장한다. */
@Component
public class WorkerPopulationCollector implements Collector {
    private final SeoulOpenApiClient client;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public WorkerPopulationCollector(SeoulOpenApiClient client, JdbcTemplate jdbc, PlatformTransactionManager tx) {
        this.client = client; this.jdbc = jdbc; this.transaction = new TransactionTemplate(tx);
    }
    public String source() { return "seoul-area-workers"; }
    public CollectResult collect(IngestContext context) {
        return transaction.execute(status -> {
            Map<String,Long> versions = new HashMap<>();
            Map<String,Integer> totals = new HashMap<>();
            Set<String> seen = new HashSet<>();
            Set<String> existing = new HashSet<>(jdbc.queryForList("SELECT quarter||':'||area_code FROM area_worker_pop_q",String.class));
            int[] counts = new int[3];
            client.pages("VwsmTrdarWrcPopltnQq", "", page -> {
                context.saveRaw("workers:"+page.start()+"-"+page.end(),page.raw());
                if (page.rows().isArray()) for (JsonNode row : page.rows()) {
                    String quarter = row.path("STDR_YYQU_CD").asText();
                    QuarterBackfillService.validate(quarter);
                    String area = row.path("TRDAR_CD").asText();
                    String key = quarter+":"+area;
                    if (area.isBlank() || !seen.add(key)) throw new IllegalArgumentException("누락 또는 중복 직장인구 키");
                    long version = versions.computeIfAbsent(quarter, q -> jdbc.queryForObject("INSERT INTO data_versions(source,period,status) VALUES (?,?,'LOADING') RETURNING id",Long.class,source(),q));
                    jdbc.update("INSERT INTO area_worker_pop_q VALUES (?,?,?,?) ON CONFLICT(quarter,area_code) DO UPDATE SET total_population=excluded.total_population,version_id=excluded.version_id",
                            quarter,area,QuarterlyCollector.exactLong(row.path("TOT_WRC_POPLTN_CO"),"TOT_WRC_POPLTN_CO"),version);
                    totals.merge(quarter,1,Integer::sum);
                    counts[0]++; counts[existing.contains(key)?2:1]++;
                }
            });
            versions.forEach((q,v) -> {
                jdbc.update("UPDATE data_versions SET status='READY' WHERE source=? AND period=? AND status='ACTIVE'",source(),q);
                jdbc.update("UPDATE data_versions SET status='ACTIVE',row_count=?,activated_at=now() WHERE id=?",totals.get(q),v);
            });
            return new CollectResult(counts[0],counts[1],counts[2],0);
        });
    }
}
