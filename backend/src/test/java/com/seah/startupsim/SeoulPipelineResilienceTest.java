package com.seah.startupsim;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.seah.startupsim.common.ExternalProperties;
import com.seah.startupsim.common.SecretMasker;
import com.seah.startupsim.ingest.IngestRepository;
import com.seah.startupsim.ingest.IngestService;
import com.seah.startupsim.ingest.QuarterlyCollector;
import com.seah.startupsim.ingest.client.SeoulApiException;
import com.seah.startupsim.ingest.client.SeoulClientProperties;
import com.seah.startupsim.ingest.client.SeoulOpenApiClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** 실제 샘플을 확장한 다중 페이지로 수집 트랜잭션과 장애 제어의 경계를 검증한다. */
@Testcontainers
@SpringBootTest
class SeoulPipelineResilienceTest {
    private static final String SOURCE = "seoul-area-sales";
    private static final String SERVICE = "VwsmTrdarSelngQq";
    private static final String QUARTER = "20262";
    private static final String KEY = "fixture-secret";
    private static final WireMockServer API = new WireMockServer(0);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static {
        API.start();
    }

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired IngestRepository repository;
    @Autowired PlatformTransactionManager transactions;
    private ObjectNode sampleRow;

    @BeforeEach
    void reset() throws Exception {
        API.resetAll();
        jdbc.execute("TRUNCATE raw_payloads,ingest_runs,data_versions RESTART IDENTITY CASCADE");
        sampleRow = (ObjectNode) MAPPER.readTree(Files.readString(Path.of(
                "../docs/data/samples/seoul_VwsmTrdarSelngQq_20262.json")))
                .path("response").path(SERVICE).path("row").get(0);
    }

    @AfterAll
    static void stop() {
        API.stop();
    }

    private IngestService service(int requestsPerSecond) {
        ExternalProperties external = new ExternalProperties();
        external.getSeoul().setApiKey(KEY);
        SecretMasker masker = new SecretMasker(external);
        // 테스트마다 정책 인스턴스를 새로 만들어 실행 순서에 따른 차단기/속도 제한 간섭을 막는다.
        SeoulClientProperties settings = new SeoulClientProperties(API.baseUrl(), requestsPerSecond,
                3, Duration.ofMillis(1), Duration.ofSeconds(1), Duration.ofSeconds(2),
                3, Duration.ofSeconds(2));
        SeoulOpenApiClient client = new SeoulOpenApiClient(settings, external, masker);
        QuarterlyCollector collector = new QuarterlyCollector(SOURCE, SERVICE, "area_sales_q", true,
                List.of(new QuarterlyCollector.Field("sales_amount", "THSMON_SELNG_AMT")),
                client, jdbc, transactions);
        return new IngestService(List.of(collector), repository, masker);
    }

    private String url(int start) {
        return "/" + KEY + "/json/" + SERVICE + "/" + start + "/" + (start + 999) + "/" + QUARTER;
    }

    private String page(int start, int count, int total, long amount) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode body = root.putObject(SERVICE);
        body.put("list_total_count", total);
        body.putObject("RESULT").put("CODE", "INFO-000");
        var rows = body.putArray("row");
        for (int i = start; i < start + count; i++) {
            ObjectNode row = sampleRow.deepCopy();
            row.put("TRDAR_CD", String.format("%07d", i));
            row.put("THSMON_SELNG_AMT", amount);
            rows.add(row);
        }
        return root.toString();
    }

    private void stubPage(int start, int count, int total, long amount) {
        API.stubFor(get(urlEqualTo(url(start)))
                .willReturn(aResponse().withBody(page(start, count, total, amount))));
    }

    @Test
    @DisplayName("전체 건수에 따라 두 페이지를 수집하고 원본 두 개와 활성 버전을 저장한다")
    void collectsTwoPagesUsingTotalCount() {
        stubPage(1, 1000, 1001, 100);
        stubPage(1001, 1, 1001, 200);
        long run = service(100).run(SOURCE, QUARTER);

        assertEquals(2, API.getAllServeEvents().size());
        assertEquals(1001, jdbc.queryForObject("SELECT count(*) FROM area_sales_q", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM raw_payloads WHERE run_id=?", Integer.class, run));
        assertEquals(List.of(SERVICE + ":20262:1-1000", SERVICE + ":20262:1001-2000"),
                jdbc.queryForList("SELECT request_key FROM raw_payloads ORDER BY id", String.class));
        var version = jdbc.queryForMap("SELECT * FROM data_versions");
        assertEquals("ACTIVE", version.get("status"));
        assertEquals(1001L, version.get("row_count"));
        assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT version_id) FROM area_sales_q", Integer.class));
        var result = jdbc.queryForMap("SELECT status,fetched,inserted,updated FROM ingest_runs WHERE id=?", run);
        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals(1001, result.get("fetched"));
        assertEquals(1001, result.get("inserted"));
        assertEquals(0, result.get("updated"));
    }

    @Test
    @DisplayName("두 번째 페이지가 실패하면 기존 분기 데이터와 활성 버전은 유지하고 실행 실패만 남긴다")
    void secondPageFailureRollsBackEntireQuarter() {
        IngestService ingest = service(100);
        stubPage(1, 1, 1, 123);
        ingest.run(SOURCE, QUARTER);
        var before = jdbc.queryForList("SELECT * FROM area_sales_q ORDER BY area_code");
        var versionsBefore = jdbc.queryForList("SELECT * FROM data_versions ORDER BY id");
        API.resetAll();
        stubPage(1, 1000, 1001, 999);
        API.stubFor(get(urlEqualTo(url(1001))).willReturn(aResponse().withStatus(503)));

        assertThrows(SeoulApiException.class, () -> ingest.run(SOURCE, QUARTER));

        assertEquals(before, jdbc.queryForList("SELECT * FROM area_sales_q ORDER BY area_code"));
        assertEquals(versionsBefore, jdbc.queryForList("SELECT * FROM data_versions ORDER BY id"));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM ingest_runs ORDER BY id DESC LIMIT 1", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM raw_payloads WHERE run_id=(SELECT max(id) FROM ingest_runs)", Integer.class));
        assertEquals(4, API.getAllServeEvents().size());
    }

    @Test
    @DisplayName("연속 서버 장애로 차단기가 열리면 다음 실행은 요청 없이 즉시 실패 이력을 남긴다")
    void circuitOpenFailsNextRunWithoutRequest() {
        IngestService ingest = service(100);
        API.stubFor(get(urlPathMatching(".*")).willReturn(aResponse().withStatus(503)));
        assertThrows(SeoulApiException.class, () -> ingest.run(SOURCE, QUARTER));
        assertEquals(3, API.getAllServeEvents().size());

        long started = System.nanoTime();
        SeoulApiException failure = assertThrows(SeoulApiException.class, () -> ingest.run(SOURCE, QUARTER));
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(failure.getMessage().contains("circuit open"));
        assertTrue(elapsed < 1500, "차단된 실행 소요 시간(ms): " + elapsed);
        assertEquals(3, API.getAllServeEvents().size(), "차단 상태에서는 외부 HTTP 호출이 없어야 한다");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ingest_runs WHERE status='FAILED'", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT error_message FROM ingest_runs ORDER BY id DESC LIMIT 1", String.class).contains("circuit open"));
    }

    @Test
    @DisplayName("초당 두 요청 제한으로 다섯 페이지 수집은 최소 1점5초 걸린다")
    void rateLimiterDelaysFivePages() {
        for (int start = 1; start <= 4001; start += 1000) {
            stubPage(start, start == 4001 ? 1 : 1000, 4001, 100);
        }
        IngestService ingest = service(2);
        long started = System.nanoTime();
        ingest.run(SOURCE, QUARTER);
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(elapsed >= 1500, "다섯 페이지 수집 소요 시간(ms): " + elapsed);
        assertEquals(5, API.getAllServeEvents().size());
        assertEquals(4001, jdbc.queryForObject("SELECT count(*) FROM area_sales_q", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM raw_payloads", Integer.class));
    }
}
