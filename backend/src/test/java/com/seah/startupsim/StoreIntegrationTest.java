package com.seah.startupsim;

import com.seah.startupsim.catalog.store.CopyStoreBulkLoader;
import com.seah.startupsim.catalog.store.JdbcStoreBulkLoader;
import com.seah.startupsim.catalog.store.JpaStoreBulkLoader;
import com.seah.startupsim.catalog.store.StoreBulkLoader;
import com.seah.startupsim.catalog.store.StoreCsvParser;
import com.seah.startupsim.catalog.store.StoreSnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@SpringBootTest
class StoreIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName
            .parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }
    @Autowired CopyStoreBulkLoader copy;
    @Autowired JdbcStoreBulkLoader batch;
    @Autowired JpaStoreBulkLoader jpa;
    @Autowired StoreCsvParser parser;
    @Autowired JdbcTemplate jdbc;
    @Autowired StoreSnapshotService snapshots;
    @TempDir Path temp;
    static String csv(int start, int end, boolean changed) {
        StringBuilder s = new StringBuilder("상가업소번호,상호명,지점명,상권업종대분류코드,상권업종중분류코드,상권업종소분류코드,상권업종소분류명,표준산업분류코드,시군구코드,행정동코드,도로명주소,경도,위도\n");
        for (int i = start; i <= end; i++) {
            s.append(i).append(",\"가상,상점\"\"이름\"").append(changed && i == 2 ? "변경" : "")
                    .append(",,I2,I21,I21201,커피,56221,11440,11440690,가상주소,")
                    .append(i == 20 ? "NaN" : "126.905").append(",37.556\n");
        }
        return s.toString();
    }
    @Test
    @DisplayName("세 방식은 따옴표 쉼표 빈값과 좌표를 동일하게 저장하고 두 번 적재해도 멱등하다")
    void equalAndIdempotent() throws Exception {
        Path file = Files.writeString(temp.resolve("sample.csv"), csv(1,20,false));
        for (StoreBulkLoader loader : List.of(copy,batch,jpa)) {
            jdbc.execute("TRUNCATE stores");
            for (int run=0; run<2; run++) {
                var count = parser.read(file, Integer.MAX_VALUE, rows -> loader.load(rows,"202609",null));
                assertEquals(20,count.fetched());
                assertEquals(1,count.failed());
                assertEquals(19,jdbc.queryForObject("SELECT count(*) FROM stores",Integer.class));
                assertEquals("가상,상점\"이름",jdbc.queryForObject("SELECT name FROM stores WHERE store_id='1'",String.class));
                assertEquals("",jdbc.queryForObject("SELECT branch_name FROM stores WHERE store_id='1'",String.class));
                assertEquals(126.905,jdbc.queryForObject("SELECT ST_X(geom) FROM stores WHERE store_id='1'",Double.class));
                assertEquals(37.556,jdbc.queryForObject("SELECT ST_Y(geom) FROM stores WHERE store_id='1'",Double.class));
            }
        }
    }
    @Test
    @DisplayName("두 스냅샷의 신규 폐업 변경을 집합 연산으로 계산한다")
    void differences() throws Exception {
        jdbc.execute("TRUNCATE stores,store_snapshot_diffs");
        snapshots.replace(Files.writeString(temp.resolve("a.csv"),csv(1,3,false)),"a",null);
        snapshots.replace(Files.writeString(temp.resolve("b.csv"),csv(2,4,true)),"b",null);
        assertEquals(List.of("CLOSED","CHANGED","NEW"),jdbc.queryForList(
                "SELECT change_type FROM store_snapshot_diffs WHERE snapshot='b' ORDER BY store_id",String.class));
    }
}
