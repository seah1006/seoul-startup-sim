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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"ADMIN_USERNAME=fixture-admin", "ADMIN_PASSWORD=fixture-password", "app.external.seoul.api-key=fixture-secret", "app.external.seoul.requests-per-second=100", "app.external.seoul.retry-wait=1ms", "app.external.seoul.failure-threshold=20"})
class SeoulCollectorIntegrationTest {
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

    String sample() throws Exception {
        return sampleBody("VwsmTrdarSelngQq");
    }
    // 숫자 토큰을 파싱/재직렬화하지 않는다. 샘플의 response 원문에서 페이지 총수만 조정한다.
    String sampleBody(String apiService) throws Exception {
        String raw = Files.readString(Path.of("../docs/data/samples/seoul_" + apiService + "_20262.json"));
        int rows = JsonMapper.builder().build().readTree(raw).path("response").path(apiService).path("row").size();
        String body = raw.substring(raw.indexOf('{', raw.indexOf("\"response\"")));
        return body.substring(0, body.lastIndexOf('}')).trim()
                .replaceFirst("\"list_total_count\"\\s*:\\s*[0-9]+", "\"list_total_count\": " + rows);
    }

    HttpResponse<String> backfill(String source) throws Exception {
        return AdminWebIntegrationTest.authenticatedPost(environment.getProperty("local.server.port"), "/admin/api/backfill/" + source + "?from=20262&to=20262");
    }

    @Test
    @DisplayName("세 수집기는 샘플 숫자 원문을 관리자 HTTP 경로로 정확히 적재한다")
    void rawSamplesThroughAdminEndpoint() throws Exception {
        String[][] datasets = {{"VwsmTrdarSelngQq", "seoul-area-sales", "area_sales_q"},
                {"VwsmTrdarStorQq", "seoul-area-stores", "area_stores_q"},
                {"VwsmTrdarFlpopQq", "seoul-area-floating-pop", "area_floating_pop_q"}};
        for (String[] dataset : datasets) {
            API.resetAll();
            API.stubFor(get(urlPathMatching(".*")).willReturn(aResponse().withBody(sampleBody(dataset[0]))));
            HttpResponse<String> response = backfill(dataset[1]);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("SUCCEEDED"), response.body());
            int expectedRows = JsonMapper.builder().build().readTree(sampleBody(dataset[0])).path(dataset[0]).path("row").size();
            assertEquals(expectedRows, jdbc.queryForObject("SELECT count(*) FROM " + dataset[2], Integer.class));
        }
        assertEquals(796270824L, jdbc.queryForObject("SELECT max(sales_amount) FROM area_sales_q", Long.class));
        assertEquals(433442L, jdbc.queryForObject("SELECT total_population FROM area_floating_pop_q WHERE area_code='3110829'", Long.class));
    }

    @Test
    @DisplayName("실수 표기와 문자열 숫자 및 7천670억 매출도 손실 없이 정수로 적재한다")
    void numericRepresentationsAndLargeAmount() throws Exception {
        String body = sample().replaceFirst("796270824", "767000000000")
                .replaceFirst("2152039", "2.152039E6")
                .replaceFirst("4072969", "4072969.0")
                .replaceFirst("166639436", "\"166639436.0\"");
        API.stubFor(get(urlPathMatching(".*")).willReturn(aResponse().withBody(body)));
        assertTrue(backfill("seoul-area-sales").body().contains("SUCCEEDED"));
        assertEquals(767000000000L, jdbc.queryForObject("SELECT max(sales_amount) FROM area_sales_q", Long.class));
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM area_sales_q WHERE sales_amount IN (767000000000,2152039,4072969,166639436,221681)", Integer.class));
    }

    @Test
    @DisplayName("소수부가 있는 숫자는 명확히 거절하고 백필 실패 결과에 실행 ID를 포함한다")
    void fractionalValueReturnsFailedRunId() throws Exception {
        API.stubFor(get(urlPathMatching(".*")).willReturn(aResponse().withBody(sample().replaceFirst("796270824", "796270824.5"))));
        var response = backfill("seoul-area-sales");
        var result = JsonMapper.builder().build().readTree(response.body()).get(0);
        assertEquals("FAILED", result.path("status").asText());
        long runId = result.path("runId").longValue();
        assertTrue(runId > 0, response.body());
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM ingest_runs WHERE id=?", String.class, runId));
        assertTrue(result.path("error").asText().contains("소수부"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM area_sales_q", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM raw_payloads WHERE run_id=?", Integer.class, runId));
    }

    @Test
    @DisplayName("실제 샘플 적재는 원본과 활성 버전을 저장하고 재실행 시 멱등이다")
    void sampleAndIdempotency() throws Exception {
        API.stubFor(get(urlPathMatching(".*")).willReturn(aResponse().withBody(sample())));
        service.run("seoul-area-sales", "20262");
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM area_sales_q", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM raw_payloads", Integer.class));
        service.run("seoul-area-sales", "20262");
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM area_sales_q", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT updated FROM ingest_runs ORDER BY id DESC LIMIT 1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM data_versions WHERE status='ACTIVE'", Integer.class));
    }
    @Test
    @DisplayName("데이터 없음은 정상 0건 성공이다")
    void noData() {
        API.stubFor(get(urlPathMatching(".*")).willReturn(aResponse().withBody("{\"RESULT\":{\"CODE\":\"INFO-200\"}}")));
        service.run("seoul-area-sales", "20262");
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM ingest_runs", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT fetched FROM ingest_runs", Integer.class));
    }
    @Test
    @DisplayName("인증 오류는 재시도 없이 실패하며 비밀 키를 저장하지 않는다")
    void authenticationFailure() {
        API.stubFor(get(urlPathMatching(".*")).willReturn(aResponse().withBody("{\"RESULT\":{\"CODE\":\"ERROR-300\"}}")));
        assertThrows(SeoulApiException.class, () -> service.run("seoul-area-sales", "20262"));
        assertEquals(1, API.getAllServeEvents().size());
        assertTrue(!jdbc.queryForObject("SELECT error_message FROM ingest_runs", String.class).contains("fixture-secret"));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM ingest_runs", String.class));
    }
    @Test
    @DisplayName("서버 오류 두 번 후 성공하면 재시도하여 성공한다")
    void retryServerError() throws Exception {
        API.stubFor(get(urlPathMatching(".*")).inScenario("retry").whenScenarioStateIs("Started").willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        API.stubFor(get(urlPathMatching(".*")).inScenario("retry").whenScenarioStateIs("second").willReturn(aResponse().withStatus(503)).willSetStateTo("success"));
        API.stubFor(get(urlPathMatching(".*")).inScenario("retry").whenScenarioStateIs("success").willReturn(aResponse().withBody(sample())));
        service.run("seoul-area-sales", "20262");
        assertEquals(3, API.getAllServeEvents().size());
    }
}

