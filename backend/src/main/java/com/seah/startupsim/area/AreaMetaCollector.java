package com.seah.startupsim.area;

import com.seah.startupsim.common.ExternalProperties;
import com.seah.startupsim.ingest.Collector;
import com.seah.startupsim.ingest.CollectResult;
import com.seah.startupsim.ingest.IngestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 서울 영역 API를 1000행씩 원본 보존 후 적재한다. 오류에는 키 포함 URL을 노출하지 않는다. */
@Component
public class AreaMetaCollector implements Collector {
    private final AreaRepository repository;
    private final ExternalProperties properties;
    private final ObjectMapper mapper;
    private final RestClient client;
    public AreaMetaCollector(AreaRepository repository, ExternalProperties properties, ObjectMapper mapper,
            @Value("${app.ingest.seoul-base-url:http://openapi.seoul.go.kr:8088}") String baseUrl) {
        this.repository = repository; this.properties = properties; this.mapper = mapper;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
    public String source() { return "seoul-area-meta"; }
    @Transactional
    public CollectResult collect(IngestContext context) {
        if (properties.getSeoul().getApiKey().isBlank()) throw new IllegalStateException("서울 API 키가 필요합니다.");
        List<JsonNode> rows = new ArrayList<>();
        int total = 1;
        for (int start = 1; start <= total; start += 1000) {
            String body;
            try {
                body = client.get().uri("/{key}/json/TbgisTrdarRelm/{start}/{end}/", properties.getSeoul().getApiKey(), start, start + 999).retrieve().body(String.class);
            } catch (RuntimeException e) {
                throw new IllegalStateException("서울 상권 메타 HTTP 요청 실패");
            }
            context.saveRaw("page-" + start, body);
            JsonNode root = mapper.readTree(body).path("TbgisTrdarRelm");
            if (!root.path("RESULT").path("CODE").asText().equals("INFO-000")) throw new IllegalStateException("서울 상권 메타 응답 오류");
            total = root.path("list_total_count").asInt();
            if (root.path("row").isEmpty()) throw new IllegalStateException("서울 상권 메타 페이지 누락");
            root.path("row").forEach(rows::add);
        }
        if (rows.size() != total) throw new IllegalStateException("상권 메타 행 수 불일치");
        long version = repository.version(source(), rows.size());
        for (JsonNode row : rows) repository.meta(row, version);
        repository.activate(source(), version);
        return new CollectResult(rows.size(), 0, rows.size(), 0);
    }
}
