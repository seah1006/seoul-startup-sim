package com.seah.startupsim;

import com.seah.startupsim.ingest.QuarterlyRefreshJob;
import com.seah.startupsim.ingest.QuarterBackfillService;
import com.seah.startupsim.ingest.client.SeoulOpenApiClient;
import com.seah.startupsim.common.SecretMasker;
import com.seah.startupsim.common.ExternalProperties;
import java.util.List;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QuarterlyRefreshJobTest {
    @Test void initialBackfillIsManual() {
        var jdbc=mock(JdbcTemplate.class); var client=mock(SeoulOpenApiClient.class);
        var backfill=mock(QuarterBackfillService.class);
        new QuarterlyRefreshJob(jdbc,client,backfill,new SecretMasker(new ExternalProperties())).refresh();
        verifyNoInteractions(client,backfill);
    }
    @Test void newlyPublishedQuarterBackfillsOnlyAvailableData() throws Exception {
        var jdbc=mock(JdbcTemplate.class); var client=mock(SeoulOpenApiClient.class);
        var backfill=mock(QuarterBackfillService.class);
        LocalDate now=LocalDate.now(ZoneId.of("Asia/Seoul")); int q=(now.getMonthValue()-1)/3+1;
        String current="%04d%d".formatted(now.getYear(),q);
        String previous="%04d%d".formatted(q==1?now.getYear()-1:now.getYear(),q==1?4:q-1);
        when(jdbc.queryForObject(anyString(),eq(String.class),anyString())).thenReturn(previous);
        var rows=JsonMapper.builder().build().readTree("[{\"STDR_YYQU_CD\":\""+current+"\"}]");
        doAnswer(call -> {
            java.util.function.Consumer<SeoulOpenApiClient.ApiPage> callback=call.getArgument(2);
            callback.accept(new SeoulOpenApiClient.ApiPage(1,1000,"{}",rows)); return null;
        }).when(client).pages(anyString(),eq(current),any());
        when(backfill.backfill(anyString(),eq(current),eq(current))).thenReturn(List.of(new QuarterBackfillService.Result(current,1L,"SUCCEEDED",null)));
        new QuarterlyRefreshJob(jdbc,client,backfill,new SecretMasker(new ExternalProperties())).refresh();
        verify(backfill,times(3)).backfill(anyString(),eq(current),eq(current));
    }
    @Test void emptyApiResponseDoesNotCreateActiveVersion() {
        var jdbc=mock(JdbcTemplate.class); var client=mock(SeoulOpenApiClient.class);
        var backfill=mock(QuarterBackfillService.class);
        when(jdbc.queryForObject(anyString(),eq(String.class),anyString())).thenReturn("20201");
        new QuarterlyRefreshJob(jdbc,client,backfill,new SecretMasker(new ExternalProperties())).refresh();
        verify(client,times(3)).pages(anyString(),eq("20202"),any());
        verifyNoInteractions(backfill);
    }
}
