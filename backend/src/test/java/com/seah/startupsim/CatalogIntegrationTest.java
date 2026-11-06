package com.seah.startupsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.seah.startupsim.ingest.IngestService;
import org.springframework.core.env.Environment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.seah.startupsim.ingest.client.SeoulApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"app.admin.enabled=true", "app.external.seoul.api-key=fixture-secret", "app.external.seoul.requests-per-second=100", "app.external.seoul.retry-wait=1ms", "app.external.seoul.failure-threshold=20"})
class CatalogIntegrationTest {
    static final WireMockServer API = new WireMockServer(0);
    static { API.start(); }
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("app.external.seoul.base-url", API::baseUrl);
    }
    @Autowired Environment environment;
    @Autowired IngestService service;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach
    void reset() throws Exception {
        API.resetAll();
        jdbc.execute("TRUNCATE raw_payloads,ingest_runs,data_versions RESTART IDENTITY CASCADE");
        for (String api : new String[]{"VwsmTrdarSelngQq","VwsmTrdarStorQq","VwsmTrdarFlpopQq"}) {
            for (var row : JsonMapper.builder().build().readTree(sampleBody(api)).path(api).path("row")) {
                jdbc.update("INSERT INTO trade_areas(area_code,name,geom,centroid) VALUES (?, '표본',ST_Multi(ST_MakeEnvelope(126,37,127,38,4326)),ST_SetSRID(ST_MakePoint(126.9,37.5),4326)) ON CONFLICT DO NOTHING",row.path("TRDAR_CD").asText());
            }
        }
    }
    @AfterAll
    static void stop() { API.stop(); }

    String sampleBody(String api) throws Exception {
        String file = "../docs/data/samples/seoul_"+api+(api.equals("VwsmTrdarWrcPopltnQq")?"":"_20262")+".json";
        var body = (tools.jackson.databind.node.ObjectNode) JsonMapper.builder().build().readTree(Files.readString(Path.of(file))).path("response");
        var data = (tools.jackson.databind.node.ObjectNode) body.path(api);
        data.put("list_total_count",data.path("row").size());
        return body.toString();
    }
    @Autowired com.seah.startupsim.catalog.CatalogService catalog;
    @Autowired com.seah.startupsim.catalog.IndustryMappingPort mappings;
    void area(String code) {
        jdbc.update("INSERT INTO trade_areas(area_code,name,district_code,geom,centroid) VALUES (?,'상권','11110',ST_Multi(ST_MakeEnvelope(126,37,127,38,4326)),ST_SetSRID(ST_MakePoint(126.9,37.5),4326)) ON CONFLICT(area_code) DO UPDATE SET district_code='11110'",code);
    }
    @Test
    @DisplayName("외식 10개 매핑과 최신 분기 매출 및 자치구 추정을 조회한다")
    void report() {
        area("a"); area("b");
        long v=jdbc.queryForObject("INSERT INTO data_versions(source,period,status) VALUES ('seoul-area-sales','20262','ACTIVE') RETURNING id",Long.class);
        jdbc.update("INSERT INTO area_stores_q(quarter,area_code,service_code,store_count,version_id) VALUES ('20262','a','CS100010',2,?),('20262','b','CS100010',4,?)",v,v);
        jdbc.update("INSERT INTO area_sales_q(quarter,area_code,service_code,sales_amount,version_id) VALUES ('20262','b','CS100010',4000,?)",v);
        var estimated=catalog.report("a",null);
        assertEquals("20262",estimated.get("quarter"));
        var row=(java.util.Map<?,?>)((java.util.List<?>)estimated.get("industries")).getFirst();
        assertEquals(2000L,row.get("quarterSales")); assertEquals(true,row.get("salesEstimated"));
        var actual=(java.util.Map<?,?>)((java.util.List<?>)catalog.report("b","20262").get("industries")).getFirst();
        assertEquals(4000L,actual.get("quarterSales")); assertEquals(false,actual.get("salesEstimated"));
        assertEquals(10,catalog.industries(true).size());
        for(int i=1;i<=10;i++) assertTrue(!mappings.smallCodes(String.format("CS100%03d",i)).isEmpty());
    }
    @Test
    @DisplayName("299m는 포함하고 301m는 제외하며 업종 필터와 최대 100건을 지킨다")
    void radius() {
        area("a"); jdbc.execute("TRUNCATE stores");
        for(int i=0;i<102;i++) jdbc.update("INSERT INTO stores(store_id,name,geom,snapshot,service_code) VALUES (?, '실제 표본',ST_Project(ST_SetSRID(ST_MakePoint(126.9,37.5),4326)::geography,?,0)::geometry,'test',?)","s"+i,i==101?301:299,i==100?"CS100001":"CS100010");
        assertEquals(100,catalog.stores("a","CS100010",126.9,37.5,300).get("count"));
        assertEquals(1,catalog.stores("a","CS100001",126.9,37.5,300).get("count"));
        assertEquals(100,catalog.stores("a",null,126.9,37.5,300).get("count"));
        assertThrows(IllegalArgumentException.class,()->catalog.stores("a",null,126.9,37.5,49));
        assertThrows(IllegalArgumentException.class,()->catalog.stores("a",null,126.9,37.5,1001));
    }
    @Test
    @DisplayName("실제 직장인구 샘플의 점영 표기와 여러 분기를 전체 호출로 저장한다")
    void workers() throws Exception {
        String api="VwsmTrdarWrcPopltnQq";
        var mapper=JsonMapper.builder().build();
        var body=(tools.jackson.databind.node.ObjectNode)mapper.readTree(sampleBody(api));
        var data=(tools.jackson.databind.node.ObjectNode)body.path(api);
        var rows=(tools.jackson.databind.node.ArrayNode)data.path("row");
        for(var r:rows) area(r.path("TRDAR_CD").asText());
        var extra=((tools.jackson.databind.node.ObjectNode)rows.get(0)).deepCopy();
        extra.put("STDR_YYQU_CD","20261"); extra.put("TOT_WRC_POPLTN_CO","123.0"); rows.add(extra); data.put("list_total_count",rows.size());
        API.stubFor(get(urlPathMatching(".*/VwsmTrdarWrcPopltnQq/1/1000/" )).willReturn(aResponse().withBody(body.toString())));
        service.run("seoul-area-workers",null);
        assertEquals(rows.size(),jdbc.queryForObject("SELECT count(*) FROM area_worker_pop_q",Integer.class));
        assertEquals(123L,jdbc.queryForObject("SELECT total_population FROM area_worker_pop_q WHERE quarter='20261'",Long.class));
    }
}
