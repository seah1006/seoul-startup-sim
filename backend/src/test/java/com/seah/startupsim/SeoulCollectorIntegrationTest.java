package com.seah.startupsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.seah.startupsim.ingest.IngestService;
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
@SpringBootTest(properties = {"app.external.seoul.api-key=fixture-secret", "app.external.seoul.requests-per-second=100", "app.external.seoul.retry-wait=1ms", "app.external.seoul.failure-threshold=20"})
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
    @Autowired IngestService service;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach
    void reset() {
        API.resetAll();
        jdbc.execute("TRUNCATE raw_payloads,ingest_runs,data_versions RESTART IDENTITY CASCADE");
    }
    @AfterAll
    static void stop() { API.stop(); }

    String sample() throws Exception {
        var mapper = JsonMapper.builder().build();
        var response = mapper.readTree(Files.readString(Path.of("../docs/data/samples/seoul_VwsmTrdarSelngQq_20262.json"))).path("response");
        ((tools.jackson.databind.node.ObjectNode) response.path("VwsmTrdarSelngQq")).put("list_total_count", 5);
        return response.toString();
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
