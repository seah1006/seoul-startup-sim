package com.seah.startupsim;

import com.seah.startupsim.game.AdvanceService;
import com.seah.startupsim.game.GameRepository;
import com.seah.startupsim.game.GameService;
import com.seah.startupsim.game.GameTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** TASK-005B: RECORD 인증글 작성·연결 조회·공유 페이지 연결. 게임은 실제 서비스로 끝내고 엔딩 원본(game_records)과 비교한다. */
@Testcontainers
@SpringBootTest(properties = {"app.board.rate-limit.posts=1000", "app.board.rate-limit.ip-posts=1000"})
class BoardRecordIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    private static final String TOKEN = "X-Guest-Token";
    private static final String POSTS = "/api/board/posts";
    private static final String JSON = "application/json";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired GameService games;
    @Autowired GameRepository repository;
    @Autowired AdvanceService advance;
    @Autowired WebApplicationContext context;
    final ObjectMapper mapper = new ObjectMapper();
    MockMvc mvc;
    BoardTestUsers.TestUser owner;
    BoardTestUsers.TestUser other;

    @BeforeEach
    void fixture() {
        jdbc.execute("TRUNCATE turning_points, turning_point_options, reaction_templates, moment_templates CASCADE");
        jdbc.execute("TRUNCATE users, trade_areas, data_versions, stores CASCADE");
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
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = BoardTestUsers.member(jdbc, "인증사장");
        other = BoardTestUsers.member(jdbc, "남의사장");
    }

    long game(long user) {
        long id = games.create(user, new GameTypes.Create("김사장", GameTypes.Background.OFFICE_WORKER,
                "c1", 123, List.of("service"), "20231")).gameId();
        games.shop(id, user, new GameTypes.Shop("A", "CS100010", 127, 38, "가상커피"));
        return id;
    }

    long endedGame(long user) {
        long id = game(user);
        jdbc.update("UPDATE games SET last_quarter = '20231' WHERE id = ?", id);
        var state = repository.state(id, user);
        games.decision(id, user, 0, new GameTypes.Decision(GameTypes.Price.NORMAL, 10, 2, 1000000, GameTypes.Activity.MENU_DEV));
        advance.advance(id, user, "rec-" + id, state.version());
        assertEquals("ENDED", jdbc.queryForObject("SELECT status FROM games WHERE id = ?", String.class, id));
        return id;
    }

    ResultActions create(BoardTestUsers.TestUser user, String body) throws Exception {
        return mvc.perform(post(POSTS).header(TOKEN, user.token()).contentType(JSON).content(body));
    }

    static String record(long gameId) {
        return "{\"category\":\"RECORD\",\"gameId\":" + gameId + ",\"title\":\" 첫 창업 \",\"content\":\" 본문 \"}";
    }

    ResultActions error(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    @Test
    @DisplayName("RECORD 성공: 스냅샷은 summary·game_records 원본과 같고, 위조 값은 무시, 조회·목록·공유 페이지에 연결")
    void createCopiesSnapshotFromEndingSource() throws Exception {
        long game = endedGame(owner.id());
        JsonNode summary = mapper.readTree(mvc.perform(get("/api/games/{id}/summary", game).header(TOKEN, owner.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var row = jdbc.queryForMap("SELECT * FROM game_records WHERE game_id = ?", game);
        String forged = "{\"category\":\"RECORD\",\"gameId\":" + game + ",\"title\":\" 첫 창업 \",\"content\":\" 본문 \","
                + "\"totalProfit\":999,\"endingType\":\"SOLD\",\"serviceCode\":\"X\",\"areaCode\":\"X\",\"shopName\":\"위조\","
                + "\"ownerName\":\"위조\",\"survivedQuarters\":99,\"serviceName\":\"위조\",\"areaName\":\"위조\",\"authorId\":-1}";
        MvcResult created = create(owner, forged).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.category").value("RECORD")).andExpect(jsonPath("$.title").value("첫 창업"))
                .andExpect(jsonPath("$.isMine").value(true)).andReturn();
        JsonNode body = mapper.readTree(created.getResponse().getContentAsString());
        JsonNode rec = body.get("record");
        // summary 응답에 있는 7개 필드
        for (String field : List.of("endingType", "serviceName", "areaName", "shopName", "ownerName")) {
            assertEquals(summary.get(field).asText(), rec.get(field).asText(), field);
        }
        assertEquals(summary.get("survivedQuarters").asInt(), rec.get("survivedQuarters").asInt());
        assertEquals(summary.get("totalProfit").asLong(), rec.get("totalProfit").asLong());
        // summary에 없는 2개 코드는 같은 엔딩 원본 행과 직접 비교
        assertEquals(row.get("service_code"), rec.get("serviceCode").asText());
        assertEquals(row.get("area_code"), rec.get("areaCode").asText());
        assertEquals(game, rec.get("gameId").asLong());
        assertEquals("/share/" + game, rec.get("shareUrl").asText());
        assertEquals("CS100010", rec.get("serviceCode").asText());
        assertEquals("A", rec.get("areaCode").asText());
        // DB의 posts 행도 같은 값
        var post = jdbc.queryForMap("SELECT * FROM posts WHERE id = ?", body.get("postId").asLong());
        assertEquals(row.get("total_profit"), post.get("total_profit"));
        assertEquals(row.get("ending_type"), post.get("ending_type"));
        assertEquals(row.get("shop_name"), post.get("shop_name"));
        assertEquals(row.get("owner_name"), post.get("owner_name"));
        assertEquals(row.get("survived_quarters"), post.get("survived_quarters"));
        assertEquals(owner.id(), ((Number) post.get("author_id")).longValue());

        long postId = body.get("postId").asLong();
        mvc.perform(get("/api/board/records/{id}", game)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.postId").value(postId)).andExpect(jsonPath("$.title").value("첫 창업"))
                .andExpect(jsonPath("$.commentCount").value(0)).andExpect(jsonPath("$.postUrl").value("/board/posts/" + postId));
        mvc.perform(get(POSTS).param("category", "RECORD").param("serviceCode", "CS100010").param("areaCode", "A"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].postId").value(postId));
        mvc.perform(get(POSTS).param("serviceCode", "NOPE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/share/{id}", game)).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/board/posts/" + postId + "\"")))
                .andExpect(content().string(containsString("첫 창업")));

        // 숨김 후 링크와 공유 섹션이 사라진다
        jdbc.update("UPDATE posts SET status = 'HIDDEN' WHERE id = ?", postId);
        mvc.perform(get("/api/board/records/{id}", game)).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get("/share/{id}", game)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/board/posts/" + postId))));
    }

    @Test
    @DisplayName("판정 순서: 401, 403(게스트), 400, 404(없음·남의 게임), 409(진행 중)")
    void rejections() throws Exception {
        long ended = endedGame(owner.id());
        long playing = game(owner.id());
        mvc.perform(post(POSTS).contentType(JSON).content(record(ended))).andExpect(status().isUnauthorized());
        var guest = BoardTestUsers.guest(jdbc, "손님");
        error(create(guest, record(ended)), 403, "MEMBER_REQUIRED");
        error(create(owner, "{\"category\":\"RECORD\",\"title\":\"t\",\"content\":\"c\"}"), 400, "VALIDATION_FAILED");
        error(create(owner, record(0)), 400, "VALIDATION_FAILED");
        error(create(owner, "{\"category\":\"RECORD\",\"gameId\":\"1\",\"title\":\"t\",\"content\":\"c\"}"), 400, "VALIDATION_FAILED");
        error(create(owner, "{\"category\":\"TIP\",\"gameId\":" + ended + ",\"title\":\"t\",\"content\":\"c\"}"), 400, "VALIDATION_FAILED");
        error(create(owner, record(999999)), 404, "NOT_FOUND");
        error(create(other, record(ended)), 404, "NOT_FOUND");
        error(create(other, record(playing)), 404, "NOT_FOUND");
        error(create(owner, record(playing)), 409, "RECORD_GAME_NOT_ENDED");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM posts WHERE category = 'RECORD'", Integer.class));
        // 400이 404보다 먼저: 남의 게임이라도 형식 오류면 400
        error(create(other, "{\"category\":\"RECORD\",\"gameId\":" + ended + ",\"title\":\"\",\"content\":\"c\"}"), 400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("같은 게임 두 번째 409, 삭제 후 재작성 201, HIDDEN이면 재작성 409, 수정에서 gameId·전환 400")
    void duplicateDeleteHiddenAndEdit() throws Exception {
        long game = endedGame(owner.id());
        MvcResult first = create(owner, record(game)).andExpect(status().isCreated()).andReturn();
        long id = mapper.readTree(first.getResponse().getContentAsString()).get("postId").asLong();
        error(create(owner, record(game)), 409, "RECORD_ALREADY_POSTED");
        mvc.perform(put(POSTS + "/{id}", id).header(TOKEN, owner.token()).contentType(JSON)
                .content("{\"category\":\"RECORD\",\"title\":\"수정\",\"content\":\"본문2\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.record.gameId").value(game));
        error(mvc.perform(put(POSTS + "/{id}", id).header(TOKEN, owner.token()).contentType(JSON)
                .content("{\"category\":\"RECORD\",\"gameId\":1,\"title\":\"수정\",\"content\":\"본문2\",\"version\":1}")), 400, "VALIDATION_FAILED");
        error(mvc.perform(put(POSTS + "/{id}", id).header(TOKEN, owner.token()).contentType(JSON)
                .content("{\"category\":\"TIP\",\"title\":\"수정\",\"content\":\"본문2\",\"version\":1}")), 400, "VALIDATION_FAILED");
        mvc.perform(delete(POSTS + "/{id}", id).header(TOKEN, owner.token()).param("version", "1")).andExpect(status().isNoContent());
        mvc.perform(get("/api/board/records/{id}", game)).andExpect(status().isNoContent());
        MvcResult second = create(owner, record(game)).andExpect(status().isCreated()).andReturn();
        long secondId = mapper.readTree(second.getResponse().getContentAsString()).get("postId").asLong();
        assertNotEquals(id, secondId);
        jdbc.update("UPDATE posts SET status = 'HIDDEN' WHERE id = ?", secondId);
        error(create(owner, record(game)), 409, "RECORD_ALREADY_POSTED");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM posts WHERE game_id = ? AND status <> 'DELETED'", Integer.class, game));
    }

    @Test
    @DisplayName("엔딩 원본이 없으면 글을 만들지 않고 500 INTERNAL_ERROR")
    void missingEndingSource() throws Exception {
        long game = endedGame(owner.id());
        jdbc.update("DELETE FROM game_records WHERE game_id = ?", game);
        error(create(owner, record(game)), 500, "INTERNAL_ERROR");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM posts WHERE category = 'RECORD'", Integer.class));
    }

    @Test
    @DisplayName("동시 RECORD 작성 2건: 201 1건, 409 1건, 활성 행 1개")
    void concurrentCreate() throws Exception {
        long game = endedGame(owner.id());
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CyclicBarrier(2);
        try {
            List<Callable<Integer>> work = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                work.add(() -> {
                    gate.await();
                    return create(owner, record(game)).andReturn().getResponse().getStatus();
                });
            }
            var statuses = new ArrayList<Integer>();
            for (var f : pool.invokeAll(work)) statuses.add(f.get());
            statuses.sort(Integer::compare);
            assertEquals(List.of(201, 409), statuses);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM posts WHERE game_id = ? AND status <> 'DELETED'", Integer.class, game));
    }

    @Test
    @DisplayName("트리거: 남의 게임·진행 중 게임 RECORD를 SQL로 직접 넣으면 거부")
    void triggerRejectsDirectInsert() {
        long ended = endedGame(owner.id());
        long playing = game(owner.id());
        String sql = """
                INSERT INTO posts (category, title, content, author_id, game_id, ending_type, service_code, service_name,
                    area_code, area_name, survived_quarters, total_profit, shop_name, owner_name)
                VALUES ('RECORD', 't', 'c', ?, ?, 'CLOSED', 'S', 'S', 'A', 'A', 1, 1, 's', 'o')
                """;
        assertThrows(DataAccessException.class, () -> jdbc.update(sql, other.id(), ended));
        assertThrows(DataAccessException.class, () -> jdbc.update(sql, owner.id(), playing));
        assertEquals(1, jdbc.update(sql, owner.id(), ended));
    }

    @Test
    @DisplayName("records 조회: 글 없음·없는 게임은 204, 공유 페이지는 로그인 없이 섹션 생략")
    void recordsAbsent() throws Exception {
        long game = endedGame(owner.id());
        mvc.perform(get("/api/board/records/{id}", game)).andExpect(status().isNoContent());
        mvc.perform(get("/api/board/records/{id}", 999999)).andExpect(status().isNoContent());
        mvc.perform(get("/share/{id}", game)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("이 가게의 인증글"))));
    }
}
