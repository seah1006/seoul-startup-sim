package com.seah.startupsim.area;

import com.seah.startupsim.ingest.Collector;
import com.seah.startupsim.ingest.CollectResult;
import com.seah.startupsim.ingest.IngestContext;
import com.seah.startupsim.ingest.client.SeoulOpenApiClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** 메타 수집도 공통 속도 제한·재시도·차단기를 거친 뒤 짧게 적재한다. */
@Component
public class AreaMetaCollector implements Collector {
    private final AreaRepository repository;
    private final SeoulOpenApiClient client;
    private final TransactionTemplate transaction;
    public AreaMetaCollector(AreaRepository repository, SeoulOpenApiClient client, PlatformTransactionManager tx) {
        this.repository=repository; this.client=client; this.transaction=new TransactionTemplate(tx);
    }
    public String source() { return "seoul-area-meta"; }
    public CollectResult collect(IngestContext context) {
        List<JsonNode> rows=new ArrayList<>();
        client.pages("TbgisTrdarRelm","",page -> {
            context.saveRaw("page-"+page.start(),page.raw());
            if (page.rows().isArray()) page.rows().forEach(rows::add);
        });
        if (rows.isEmpty()) throw new IllegalStateException("상권 메타 응답이 비어 있습니다.");
        return transaction.execute(status -> {
            long version=repository.version(source(),rows.size());
            for(JsonNode row:rows) repository.meta(row,version);
            repository.activate(source(),version);
            return new CollectResult(rows.size(),0,rows.size(),0);
        });
    }
}
