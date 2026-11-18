package com.seah.startupsim.game;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 엔딩이 나는 모든 경로가 같은 트랜잭션에서 game_records를 정확히 1행 남기는지 검증한다. */
@Testcontainers
@SpringBootTest
class EndingIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired GuestAuth auth;
    @Autowired GameService games;
    @Autowired GameRepository repository;
    @Autowired AdvanceService advance;
    long user;
    String token;

    @BeforeEach
    void fixture() {
        jdbc.execute("TRUNCATE turning_points, turning_point_options, reaction_templates, moment_templates CASCADE");
        jdbc.execute("TRUNCATE users, trade_areas, data_versions, stores CASCADE");
        var guest = auth.create();
        user = guest.userId();
        token = guest.guestToken();
        jdbc.update("INSERT INTO data_versions (id, source, status) VALUES (100, 'fixture', 'ACTIVE')");
        jdbc.update("""
                INSERT INTO trade_areas (area_code, name, district_code, geom, centroid, version_id, area_m2)
                VALUES ('A', '테스트 상권', '11440',
                    ST_Multi(ST_GeomFromText('POLYGON((126 37,128 37,128 39,126 39,126 37))',4326)),
                    ST_SetSRID(ST_MakePoint(127,38),4326), 100, 1000000)
                """);
        for (String quarter : List.of("20231", "20232", "20233", "20234", "20241", "20242")) {
            jdbc.update("""
                    INSERT INTO area_sales_q (quarter, area_code, service_code, sales_amount, version_id)
                    VALUES (?, 'A', 'CS100010', 1000000000, 100)
                    """, quarter);
            jdbc.update("""
                    INSERT INTO area_stores_q (quarter, area_code, service_code, store_count,
                                               opening_count, closing_count, version_id)
                    VALUES (?, 'A', 'CS100010', 10, 2, 1, 100)
                    """, quarter);
        }
    }

    long game() {
        long id = games.create(user, new GameTypes.Create("김사장", GameTypes.Background.OFFICE_WORKER,
                "c1", 123, List.of("service"), "20231")).gameId();
        games.shop(id, user, new GameTypes.Shop("A", "CS100010", 127, 38, "가상 가게"));
        return id;
    }

    GameResponses.Advanced next(long id, String key) {
        var state = repository.state(id, user);
        games.decision(id, user, state.currentTurn(), new GameTypes.Decision(GameTypes.Price.NORMAL,
                10, 2, 1000000, GameTypes.Activity.MENU_DEV));
        return advance.advance(id, user, key, state.version());
    }

    int records(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM game_records WHERE game_id = ?", Integer.class, id);
    }

    @Test
    @DisplayName("LONG_RUNNING 엔딩은 결산 이력과 엔딩 시점 스냅샷으로 기록 1행을 남기고 재시도해도 늘지 않는다")
    void longRunningRecord() {
        long id = game();
        jdbc.update("UPDATE games SET last_quarter = '20232' WHERE id = ?", id);
        next(id, "q0");
        assertEquals(0, records(id));
        var ended = next(id, "q1");
        assertEquals("LONG_RUNNING", ended.game().endingType());
        assertEquals(ended, advance.advance(id, user, "q1", ended.game().version() - 1));
        assertEquals(1, records(id));
        var row = jdbc.queryForMap("SELECT * FROM game_records WHERE game_id = ?", id);
        assertEquals(user, row.get("user_id"));
        assertEquals(jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, user), row.get("nickname"));
        assertEquals("LONG_RUNNING", row.get("ending_type"));
        assertEquals(2, row.get("survived_quarters"));
        assertEquals(jdbc.queryForObject("SELECT sum(profit) FROM turn_results WHERE game_id = ?", Long.class, id),
                row.get("total_profit"));
        assertEquals(jdbc.queryForObject("SELECT max(profit) FROM turn_results WHERE game_id = ?", Long.class, id),
                row.get("best_quarter_profit"));
        assertEquals(1, row.get("shop_count"));
        assertNull(row.get("sale_value"));
        assertEquals(List.of("A", "테스트 상권", "CS100010", "커피-음료", "가상 가게", "김사장"),
                List.of(row.get("area_code"), row.get("area_name"), row.get("service_code"), row.get("service_name"),
                        row.get("shop_name"), row.get("owner_name")));
        Instant endedAt = ((java.sql.Timestamp) row.get("ended_at")).toInstant();
        assertEquals(GameRecordRepository.rankingDate(endedAt), ((java.sql.Date) row.get("ranking_date")).toLocalDate());
    }

    @Test
    @DisplayName("CLOSED 엔딩은 2분기 연속 음수 잔액에서 기록 1행을 남긴다")
    void closedRecord() {
        long id = game();
        jdbc.update("UPDATE games SET cash = -100000000000 WHERE id = ?", id);
        next(id, "c0");
        var ended = next(id, "c1");
        assertEquals("CLOSED", ended.game().endingType());
        assertEquals(1, records(id));
        assertEquals("CLOSED", jdbc.queryForObject("SELECT ending_type FROM game_records WHERE game_id = ?", String.class, id));
    }

    @Test
    @DisplayName("랭킹 날짜는 KST 기준: 23:50은 그날, 00:10은 다음 날")
    void rankingDateUsesKst() {
        assertEquals(LocalDate.parse("2026-11-18"), GameRecordRepository.rankingDate(Instant.parse("2026-11-18T14:50:00Z")));
        assertEquals(LocalDate.parse("2026-11-19"), GameRecordRepository.rankingDate(Instant.parse("2026-11-18T15:10:00Z")));
    }
}
