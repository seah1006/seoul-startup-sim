package com.seah.startupsim;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.seah.startupsim.area.AreaRepository;
import com.seah.startupsim.area.AreaService;
import com.seah.startupsim.area.ShapeReader;
import com.seah.startupsim.ingest.IngestService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.ingest.area-shapefile=src/test/resources/area/trdar.shp")
class AreaWebIntegrationTest {
    static final WireMockServer SEOUL = new WireMockServer(0);
    static { SEOUL.start(); }

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("app.ingest.seoul-base-url", SEOUL::baseUrl);
        r.add("app.external.seoul.api-key", () -> "test-area-key");
    }

    @AfterAll
    static void stopMock() { SEOUL.stop(); }

    @Autowired IngestService ingest;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Autowired AreaRepository repository;
    @Autowired AreaService areas;

    @BeforeEach
    void reset() {
        SEOUL.resetAll();
        jdbc.execute("TRUNCATE trade_areas,raw_payloads,ingest_runs,data_versions RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("공식 영역 샘플을 WireMock으로 재생하고 메타 원본과 활성 버전을 보존한다")
    void metadataReplay() throws Exception {
        ingest.run("seoul-area-shapes", null);
        JsonNode response = mapper.readTree(Files.readString(
                Path.of("../docs/data/samples/seoul_TbgisTrdarRelm.json"))).path("response");
        ObjectNode root = (ObjectNode) response.path("TbgisTrdarRelm");
        // 공식 샘플은 전체 1650건 중 10건이다. 재생에서는 표본 크기를 전체 건수로 선언한다.
        root.put("list_total_count", root.path("row").size());
        for (JsonNode row : root.path("row")) {
            jdbc.update("""
                    INSERT INTO trade_areas(area_code,name,geom,centroid)
                    SELECT ?,'메타 갱신 전',geom,centroid FROM trade_areas LIMIT 1
                    ON CONFLICT(area_code) DO NOTHING
                    """, row.path("TRDAR_CD").asText());
        }
        String payload = mapper.writeValueAsString(response);
        SEOUL.stubFor(get(urlEqualTo("/test-area-key/json/TbgisTrdarRelm/1/1000/"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(payload)));
        ingest.run("seoul-area-meta", null);
        int count = jdbc.queryForObject("SELECT count(*) FROM trade_areas", Integer.class);
        ingest.run("seoul-area-meta", null);
        assertEquals(count, jdbc.queryForObject("SELECT count(*) FROM trade_areas", Integer.class));
        for (JsonNode row : root.path("row")) {
            assertEquals(row.path("TRDAR_CD_NM").asText(),
                    areas.detail(row.path("TRDAR_CD").asText()).get("name"));
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM raw_payloads WHERE source='seoul-area-meta' AND payload->'TbgisTrdarRelm'->'row' IS NOT NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM data_versions WHERE source='seoul-area-meta' AND status='ACTIVE'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM data_versions WHERE source='seoul-area-meta' AND status='READY'", Integer.class));
        SEOUL.verify(2, getRequestedFor(urlEqualTo("/test-area-key/json/TbgisTrdarRelm/1/1000/")));
    }

    @Test
    @DisplayName("메타 API 오류는 원본과 실패 이력을 남기고 활성 버전을 만들지 않는다")
    void metadataFailure() {
        ingest.run("seoul-area-shapes", null);
        SEOUL.stubFor(get(urlEqualTo("/test-area-key/json/TbgisTrdarRelm/1/1000/"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"RESULT\":{\"CODE\":\"ERROR-300\"}}")));
        assertThrows(IllegalStateException.class, () -> ingest.run("seoul-area-meta", null));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ingest_runs WHERE source='seoul-area-meta' AND status='FAILED'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM raw_payloads WHERE source='seoul-area-meta'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM data_versions WHERE source='seoul-area-meta'", Integer.class));
    }

    @Test
    @DisplayName("HTTP로 bbox와 검색 및 상세를 조회하고 잘못된 요청과 누락 코드를 구분한다")
    void webEndpoints() throws Exception {
        ingest.run("seoul-area-shapes", null);
        HttpResponse<String> bbox = request("/api/areas?bbox=126.7,37.4,127.3,37.7&zoom=15");
        assertEquals(200, bbox.statusCode());
        JsonNode collection = mapper.readTree(bbox.body());
        assertEquals("FeatureCollection", collection.path("type").asText());
        assertEquals(5, collection.path("features").size());
        assertFalse(collection.path("truncated").asBoolean());
        assertTrue(java.util.Set.of("Polygon", "MultiPolygon").contains(collection.path("features").get(0).path("geometry").path("type").asText()));
        HttpResponse<String> search = request("/api/areas/search?q=" + URLEncoder.encode("배화", StandardCharsets.UTF_8));
        assertEquals(200, search.statusCode());
        JsonNode matches = mapper.readTree(search.body());
        assertEquals(1, matches.size());
        String code = matches.get(0).path("area_code").asText();
        HttpResponse<String> detail = request("/api/areas/" + code);
        assertEquals(200, detail.statusCode());
        JsonNode area = mapper.readTree(detail.body());
        assertEquals(code, area.path("area_code").asText());
        assertTrue(area.path("longitude").asDouble() > 126.7);
        assertTrue(area.path("latitude").asDouble() > 37.4);
        assertEquals(0, mapper.readTree(request("/api/areas?bbox=120,30,121,31&zoom=15").body()).path("features").size());
        assertEquals(400, request("/api/areas?bbox=bad&zoom=15").statusCode());
        assertEquals(404, request("/api/areas/nonexistent").statusCode());
    }

    @Test
    @DisplayName("자기교차 원본은 보정 표시와 유효한 MultiPolygon으로 저장하고 재적재 시 표시를 갱신한다")
    void repairGeometry() throws Exception {
        ShapeReader.Row original = new ShapeReader().read(Path.of("src/test/resources/area/trdar.shp")).getFirst();
        long version = repository.version("seoul-area-shapes", 1);
        byte[] invalid = new WKBWriter().write(new WKTReader().read(
                "POLYGON((197000 453000,197100 453100,197100 453000,197000 453100,197000 453000))"));
        repository.shape(new ShapeReader.Row(original.fields(), invalid), version);
        assertTrue(jdbc.queryForObject("SELECT geom_repaired AND ST_IsValid(geom) AND GeometryType(geom)='MULTIPOLYGON' FROM trade_areas", Boolean.class));
        repository.shape(original, version);
        assertFalse(jdbc.queryForObject("SELECT geom_repaired FROM trade_areas", Boolean.class));
    }

    private HttpResponse<String> request(String uri) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"
                + environment.getRequiredProperty("local.server.port") + uri)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
