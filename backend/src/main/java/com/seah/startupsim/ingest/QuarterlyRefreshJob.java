package com.seah.startupsim.ingest;

import com.seah.startupsim.common.SecretMasker;
import com.seah.startupsim.ingest.client.SeoulOpenApiClient;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 이미 백필된 최신 분기 이후만 확인한다. 빈 응답은 새 활성 버전으로 만들지 않는다. */
@Component
@ConditionalOnProperty(name="app.ingest.schedule.enabled",havingValue="true")
public class QuarterlyRefreshJob {
    private static final Logger log=LoggerFactory.getLogger(QuarterlyRefreshJob.class);
    private static final Map<String,String> SOURCES=Map.of("seoul-area-sales","VwsmTrdarSelngQq",
            "seoul-area-stores","VwsmTrdarStorQq","seoul-area-floating-pop","VwsmTrdarFlpopQq");
    private final JdbcTemplate jdbc;
    private final SeoulOpenApiClient client;
    private final QuarterBackfillService backfill;
    private final SecretMasker masker;
    public QuarterlyRefreshJob(JdbcTemplate jdbc, SeoulOpenApiClient client, QuarterBackfillService backfill, SecretMasker masker) {
        this.jdbc=jdbc; this.client=client; this.backfill=backfill; this.masker=masker;
    }
    @Scheduled(cron="${app.ingest.schedule.cron:0 0 4 * * *}",zone="Asia/Seoul")
    public void refresh() {
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        String current="%04d%d".formatted(today.getYear(),(today.getMonthValue()-1)/3+1);
        SOURCES.forEach((source,service)-> {
            try {
                String latest=jdbc.queryForObject("SELECT max(period) FROM data_versions WHERE source=? AND status='ACTIVE' AND row_count>0",String.class,source);
                if(latest==null) { log.info("초기 백필이 필요하여 자동 갱신 생략: {}",source); return; }
                for(String quarter=next(latest);quarter.compareTo(current)<=0;quarter=next(quarter)) {
                    boolean[] found={false};
                    client.pages(service,quarter,page -> { if(page.rows().isArray() && !page.rows().isEmpty()) found[0]=true; });
                    if(!found[0]) break;
                    var results=backfill.backfill(source,quarter,quarter);
                    if(results.stream().anyMatch(r->!"SUCCEEDED".equals(r.status()))) break;
                }
            } catch(RuntimeException e) { log.warn("분기 자동 갱신 실패 {}: {}",source,masker.mask(e.getMessage())); }
        });
    }
    static String next(String quarter) {
        QuarterBackfillService.validate(quarter);
        int year=Integer.parseInt(quarter.substring(0,4));
        int q=quarter.charAt(4)-'0';
        return "%04d%d".formatted(q==4?year+1:year,q==4?1:q+1);
    }
}
