package com.seah.startupsim;

import com.seah.startupsim.common.MoneyFormat;
import com.seah.startupsim.game.AdvanceService;
import com.seah.startupsim.game.GameRecordRepository;
import com.seah.startupsim.game.GameRepository;
import com.seah.startupsim.game.GameService;
import com.seah.startupsim.game.GameTypes;
import com.seah.startupsim.game.GuestAuth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 로그인 없이 열리는 엔딩 공유 페이지의 404, OG 이스케이프, 비공개 정보 배제를 검증한다. */
@Testcontainers
@SpringBootTest
class ShareWebIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    private static final String SHOP = "<script>alert('x')</script>\"망원";

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
    @Autowired WebApplicationContext context;
    MockMvc mvc;
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
        for (String quarter : List.of("20231", "20232")) {
            jdbc.update("INSERT INTO area_sales_q (quarter, area_code, service_code, sales_amount, version_id) VALUES (?, 'A', 'CS100010', 1000000000, 100)", quarter);
            jdbc.update("INSERT INTO area_stores_q (quarter, area_code, service_code, store_count, opening_count, closing_count, version_id) VALUES (?, 'A', 'CS100010', 10, 2, 1, 100)", quarter);
        }
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    long game() {
        long id = games.create(user, new GameTypes.Create("<b>사장</b>", GameTypes.Background.OFFICE_WORKER,
                "c1", 123, List.of("service"), "20231")).gameId();
        games.shop(id, user, new GameTypes.Shop("A", "CS100010", 127, 38, SHOP));
        return id;
    }

    void play(long id, int quarters) {
        for (int turn = 0; turn < quarters; turn++) {
            var state = repository.state(id, user);
            games.decision(id, user, turn, new GameTypes.Decision(GameTypes.Price.NORMAL, 10, 2, 1000000,
                    GameTypes.Activity.MENU_DEV));
            advance.advance(id, user, "share-" + turn, state.version());
        }
    }

    @Test
    @DisplayName("없는 게임·진행 중 게임·숫자가 아닌 ID는 로그인 없이 404 HTML")
    void notFound() throws Exception {
        long playing = game();
        play(playing, 1);
        for (String path : List.of("/share/999999", "/share/" + playing, "/share/abc", "/share/0")) {
            mvc.perform(get(path)).andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(content().string(containsString("엔딩 기록을 찾을 수 없습니다")));
        }
    }

    @Test
    @DisplayName("엔딩 공유: 로그인 불필요, OG 태그 이스케이프, 게스트 토큰·잔액·결정 같은 비공개 정보 없음")
    void sharePage() throws Exception {
        long id = game();
        play(id, 2);
        long cash = repository.state(id, user).cash();
        var record = jdbc.queryForMap("SELECT total_profit, survived_quarters FROM game_records WHERE game_id = ?", id);
        String html = mvc.perform(get("/share/" + id)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(html.contains("<script>alert"), "가게 이름 스크립트가 그대로 나가면 안 된다");
        assertFalse(html.contains("<b>사장</b>"));
        assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&quot;망원"), html);
        assertTrue(html.contains("<meta property=\"og:title\" content=\"&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&quot;망원 · 장수 가게\">"), html);
        assertTrue(html.contains("<meta property=\"og:description\" content=\"2분기 생존 · 누적 순이익 "
                + MoneyFormat.won((Long) record.get("total_profit")) + "\">"), html);
        assertTrue(html.contains("<dd>" + MoneyFormat.won((Long) record.get("total_profit")) + "</dd>"), html);
        var best = jdbc.queryForObject("SELECT best_quarter_profit FROM game_records WHERE game_id = ?", Long.class, id);
        assertTrue(html.contains("<dd>" + MoneyFormat.won(best) + "</dd>"), html);
        assertTrue(html.contains("<meta property=\"og:image\" content=\"http://localhost/og/share-default.jpg\">"), html);
        assertTrue(html.contains("<meta property=\"og:url\" content=\"http://localhost/share/" + id + "\">"), html);
        assertFalse(html.contains(token));
        assertFalse(html.contains("%,d".formatted(cash)) || html.contains(Long.toString(cash)), "잔액은 공유하지 않는다");
        for (String secret : List.of("guestToken", "X-Guest-Token", "잔액", "MENU_DEV", "priceLevel", "userId")) {
            assertFalse(html.contains(secret), secret);
        }
        mvc.perform(get("/og/share-default.jpg")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/jpeg"));
    }

    @Test
    @DisplayName("탈퇴한 사용자의 공유 페이지는 닉네임 대신 '탈퇴한 사장님'을 보여 준다")
    void withdrawnNickname() throws Exception {
        long id = game();
        jdbc.update("UPDATE games SET last_quarter = '20231' WHERE id = ?", id);
        play(id, 1);
        String nickname = jdbc.queryForObject("SELECT nickname FROM game_records WHERE game_id = ?", String.class, id);
        mvc.perform(get("/share/" + id)).andExpect(status().isOk()).andExpect(content().string(containsString(nickname)));
        jdbc.update("UPDATE users SET deleted_at = now(), nickname = '탈퇴한 사장님' WHERE id = ?", user);
        String html = mvc.perform(get("/share/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains(GameRecordRepository.DELETED_NICKNAME));
        assertFalse(html.contains(nickname));
    }
}
