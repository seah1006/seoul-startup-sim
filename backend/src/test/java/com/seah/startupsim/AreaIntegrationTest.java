package com.seah.startupsim;

import com.seah.startupsim.area.ShapeReader;
import com.seah.startupsim.ingest.IngestService;
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
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest(properties = "app.ingest.area-shapefile=src/test/resources/area/trdar.shp")
class AreaIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }
    @Autowired IngestService ingest;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.seah.startupsim.area.AreaService areas;

    @Test
    @DisplayName("실제 SHP 5행을 서울 좌표로 적재하고 재실행해도 행 수와 활성 버전 수가 유지된다")
    void shapes() {
        ingest.run("seoul-area-shapes", null);
        ingest.run("seoul-area-shapes", null);
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM trade_areas", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM trade_areas WHERE ST_SRID(geom)=4326 AND ST_IsValid(geom) AND ST_X(centroid) BETWEEN 126.7 AND 127.3 AND ST_Y(centroid) BETWEEN 37.4 AND 37.7", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM data_versions WHERE source='seoul-area-shapes' AND status='ACTIVE'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM data_versions WHERE source='seoul-area-shapes' AND status='READY'", Integer.class));
        assertEquals(5, ((java.util.List<?>) areas.areas("126.7,37.4,127.3,37.7", 15).get("features")).size());
        assertEquals(0, ((java.util.List<?>) areas.areas("120,30,121,31", 15).get("features")).size());
        assertEquals(1, areas.search("배화").size());
    }
    @Test
    @DisplayName("업종 100개 중 매출 제공 업종은 62개다")
    void industries() {
        assertEquals(100, jdbc.queryForObject("SELECT count(*) FROM service_industries", Integer.class));
        assertEquals(62, jdbc.queryForObject("SELECT count(*) FROM service_industries WHERE has_sales", Integer.class));
    }
    @Test
    @DisplayName("잘못된 bbox와 누락된 SHP는 명확히 거절한다")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> areas.areas("NaN,1,2,3", 15));
        assertThrows(IllegalArgumentException.class, () -> areas.areas("2,1,1,3", 15));
        assertThrows(IllegalArgumentException.class, () -> new ShapeReader().read(Path.of("missing.shp")));
    }
}
