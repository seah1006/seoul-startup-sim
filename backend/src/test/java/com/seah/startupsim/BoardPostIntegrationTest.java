package com.seah.startupsim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
class BoardPostIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    private static final String POSTS = "/api/board/posts";
    private static final String MINE = "/api/board/me/posts";
    private static final String TOKEN = "X-Guest-Token";
    private static final String JSON = "application/json";
    private static final String CREATE = """
            {"category":"TIP","title":" 제목 ","content":" 본문 ","authorId":-1}
            """;
    private static final String UPDATE = """
            {"category":"TIP","title":" 수정 제목 ","content":" 수정 본문 ","version":0}
            """;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired PlatformTransactionManager transactions;
    MockMvc mvc;
    Guest owner;
    Guest other;

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.execute("TRUNCATE users CASCADE");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = guest("서울사장");
        other = guest("망원사장");
    }

    @Test
    void pagingAndSortKeepMatchingPinnedFirst() throws Exception {
        long pinned = fixture(owner, "FREE", "고정", "본문");
        long popular = fixture(owner, "FREE", "인기", "본문");
        long viewed = fixture(owner, "FREE", "조회", "본문");
        long commented = fixture(owner, "FREE", "댓글", "본문");
        long latest = fixture(owner, "TIP", "최신", "본문");
        jdbc.update("UPDATE posts SET is_pinned = true WHERE id = ?", pinned);
        jdbc.update("UPDATE posts SET like_count = 10 WHERE id = ?", popular);
        jdbc.update("UPDATE posts SET view_count = 10 WHERE id = ?", viewed);
        jdbc.update("UPDATE posts SET comment_count = 10 WHERE id = ?", commented);
        String[] sorts = {"latest", "popular", "views", "comments"};
        long[] second = {latest, popular, viewed, commented};
        for (int i = 0; i < sorts.length; i++) {
            mvc.perform(get(POSTS).param("sort", sorts[i]).param("size", "2"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.content[0].postId").value(pinned))
                    .andExpect(jsonPath("$.content[0].isPinned").value(true))
                    .andExpect(jsonPath("$.content[0].content").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.content[0].authorId").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.content[1].postId").value(second[i]))
                    .andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.totalPages").value(3));
        }
        mvc.perform(get(POSTS).param("category", "TIP"))
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].postId").value(latest));
        mvc.perform(get(POSTS).param("page", "999").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page").value(999)).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.totalPages").value(3));
        for (String[] invalid : new String[][] {{"page", "-1"}, {"size", "51"}, {"size", "0"},
                {"sort", "unknown"}, {"searchType", "unknown"}, {"category", "unknown"},
                {"q", "x".repeat(101)}, {"serviceCode", " "}, {"areaCode", "x".repeat(33)}}) {
            error(mvc.perform(get(POSTS).param(invalid[0], invalid[1])), 400, "VALIDATION_FAILED");
        }
    }

    @Test
    void searchesUseLiteralCaseInsensitivePatternsAndCurrentNickname() throws Exception {
        long literal = fixture(owner, "FREE", "특수 Ab\\%_문자", "잡담");
        long body = fixture(other, "TIP", "본문에만", "특수 ab\\%_문자");
        fixture(other, "FREE", "특수 AbX문자", "특수 abX문자");
        search("title", " ab\\%_ ", literal);
        search("content", "AB\\%_", body);
        search("titleContent", "AB\\%_", body, literal);
        mvc.perform(get(POSTS).param("q", "AB\\%_"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        jdbc.update("UPDATE users SET nickname = '새 닉네임' WHERE id = ?", owner.id());
        search("author", "닉네임", literal);
        mvc.perform(get(POSTS).param("q", "서울사장").param("searchType", "author"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void recordReadAndServiceAreaFilters() throws Exception {
        long game = jdbc.queryForObject("""
                INSERT INTO games (user_id, seed, start_quarter, last_quarter, status, cash, initial_capital, settings)
                VALUES (?, 1, '20231', '20231', 'ENDED', 0, 0, '{}') RETURNING id
                """, Long.class, owner.id());
        long record = jdbc.queryForObject("""
                INSERT INTO posts (author_id, game_id, category, title, content, ending_type, service_code,
                    service_name, area_code, area_name, shop_name, owner_name, survived_quarters, total_profit)
                VALUES (?, ?, 'RECORD', '가상의 창업', '본문', 'LONG_RUNNING', 'CS100010', '커피-음료', '3110123',
                    '망원역', '가상 가게', '가상 사장', 14, 182000000) RETURNING id
                """, Long.class, owner.id(), game);
        fixture(owner, "FREE", "일반 게시판", "본문");
        for (String code : new String[] {"serviceCode", "areaCode"}) {
            mvc.perform(get(POSTS).param(code, code.equals("serviceCode") ? " CS100010 " : " 3110123 "))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].postId").value(record))
                    .andExpect(jsonPath("$.content[0].record.shareUrl").value("/share/" + game));
            error(mvc.perform(get(POSTS).param("category", "FREE").param(code, "irrelevant")), 400, "VALIDATION_FAILED");
        }
        mvc.perform(get(POSTS).param("serviceCode", "CS100010").param("areaCode", "missing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(POSTS + "/{id}", record)).andExpect(status().isOk())
                .andExpect(jsonPath("$.record.totalProfit").value(182000000));
        update(record, owner, UPDATE.replace("TIP", "RECORD")).andExpect(status().isOk())
                .andExpect(jsonPath("$.record.gameId").value(game));
        error(update(record, owner, UPDATE), 400, "VALIDATION_FAILED");
    }

    @Test
    void createAuthenticatesBeforeBindingAndValidatesRequest() throws Exception {
        error(mvc.perform(post(POSTS).contentType(JSON).content("{")), 401, "UNAUTHORIZED");
        error(mvc.perform(post(POSTS).header(TOKEN, "invalid").contentType(JSON).content("{")), 401, "UNAUTHORIZED");
        for (String payload : new String[] {"{", "{}", CREATE.replace("TIP", "MISSING"),
                CREATE.replace("\"TIP\"", "0"), CREATE.replace("\" 제목 \"", "123"),
                CREATE.replace(" 제목 ", "   "), CREATE.replace(" 제목 ", "x".repeat(101)),
                CREATE.replace(" 본문 ", "x".repeat(10001))}) {
            error(create(payload), 400, "VALIDATION_FAILED");
        }
        error(create(CREATE.replace("TIP", "RECORD")), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.message").value("엔딩 인증글은 아직 작성할 수 없습니다."));
        var created = create(CREATE).andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("제목"))
                .andExpect(jsonPath("$.content").value("본문")).andExpect(jsonPath("$.viewCount").value(0))
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.isMine").value(true))
                .andExpect(jsonPath("$.record").hasJsonPath()).andExpect(jsonPath("$.record").isEmpty()).andReturn();
        long id = jdbc.queryForObject("SELECT id FROM posts WHERE author_id = ?", Long.class, owner.id());
        assertEquals(POSTS + "/" + id, created.getResponse().getHeader("Location"));
    }

    @Test
    void detailCountsAuthenticatedViewsOncePerKstDayAndKeepsVersionAndTimestamp() throws Exception {
        long id = fixture(owner, "TIP", "조회", "본문");
        var before = jdbc.queryForMap("SELECT version, updated_at FROM posts WHERE id = ?", id);
        mvc.perform(get(POSTS + "/{id}", id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.viewCount").value(0)).andExpect(jsonPath("$.isMine").value(false))
                .andExpect(jsonPath("$.likedByMe").value(false));
        for (String invalid : new String[] {"invalid", ""}) {
            error(mvc.perform(get(POSTS + "/{id}", id).header(TOKEN, invalid)), 401, "UNAUTHORIZED");
        }
        jdbc.update("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)", id, owner.id());
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(get(POSTS + "/{id}", id).header(TOKEN, owner.token())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.viewCount").value(1)).andExpect(jsonPath("$.isMine").value(true))
                    .andExpect(jsonPath("$.likedByMe").value(true));
        }
        assertEquals(day, jdbc.queryForObject("SELECT viewed_on FROM post_views WHERE post_id = ?", LocalDate.class, id));
        assertEquals(before, jdbc.queryForMap("SELECT version, updated_at FROM posts WHERE id = ?", id));
        mvc.perform(get(POSTS + "/{id}", id).header(TOKEN, other.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(2)).andExpect(jsonPath("$.isMine").value(false));
    }

    @Test
    void updateDeleteAndMineRespectValidationPrivacyOwnershipAndVersion() throws Exception {
        long active = fixture(owner, "FREE", "활성 글", "본문");
        long hidden = fixture(owner, "FREE", "숨긴 글", "본문");
        fixture(other, "TIP", "다른 글", "본문");
        jdbc.update("UPDATE posts SET status = 'HIDDEN', hidden_at = now() WHERE id = ?", hidden);
        error(mvc.perform(get(MINE).param("page", "bad")), 401, "UNAUTHORIZED");
        error(mvc.perform(delete(POSTS + "/bad")), 401, "UNAUTHORIZED");
        error(update(active, other, UPDATE), 403, "FORBIDDEN");
        error(remove(active, other, "0"), 403, "FORBIDDEN");
        error(update(hidden, owner, UPDATE.replace("0", "999")), 404, "NOT_FOUND");
        error(remove(hidden, owner, "999"), 404, "NOT_FOUND");
        error(mvc.perform(get(POSTS + "/{id}", hidden).header(TOKEN, owner.token())), 404, "NOT_FOUND");
        error(remove(active, owner, null), 400, "VALIDATION_FAILED");
        error(remove(active, owner, "-1"), 400, "VALIDATION_FAILED");
        error(update(active, owner, UPDATE.replace(",\"version\":0", "")), 400, "VALIDATION_FAILED");
        error(update(active, owner, UPDATE.replace("\"version\":0", "\"version\":0.9")), 400, "VALIDATION_FAILED");
        error(update(active, owner, UPDATE.replace("\"version\":0", "\"version\":\"0\"")), 400, "VALIDATION_FAILED");
        error(update(active, owner, UPDATE.replace(",\"version\":0", ",\"version\":0,\"gameId\":null")), 400, "VALIDATION_FAILED");
        error(update(active, owner, UPDATE.replace("TIP", "RECORD")), 400, "VALIDATION_FAILED");
        update(active, owner, UPDATE).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.title").value("수정 제목")).andExpect(jsonPath("$.category").value("TIP"))
                .andExpect(jsonPath("$.viewCount").value(0));
        error(update(active, owner, UPDATE), 409, "POST_VERSION_CONFLICT");
        error(remove(active, owner, "0"), 409, "POST_VERSION_CONFLICT");
        mvc.perform(get(MINE).header(TOKEN, owner.token())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].status").value("HIDDEN")).andExpect(jsonPath("$.content[1].version").value(1));
        remove(active, owner, "1").andExpect(status().isNoContent()).andExpect(content().string(""));
        error(remove(active, owner, "1"), 404, "NOT_FOUND");
        error(mvc.perform(get(POSTS + "/{id}", active)), 404, "NOT_FOUND");
        mvc.perform(get(MINE).header(TOKEN, owner.token())).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(POSTS)).andExpect(jsonPath("$.totalElements").value(1));
        assertEquals("DELETED", jdbc.queryForObject("SELECT status FROM posts WHERE id = ?", String.class, active));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM post_views", Long.class));
    }

    @Test
    void zeroRowUpdateRechecksRecentlyHiddenPostAsNotFound() throws Exception {
        long id = fixture(owner, "FREE", "동시 수정", "본문");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var outcome = new TransactionTemplate(transactions).execute(tx -> {
                jdbc.update("UPDATE posts SET status = 'HIDDEN', hidden_at = now() WHERE id = ?", id);
                var request = executor.submit(() -> update(id, owner, UPDATE));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !request.isDone()) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                                WHERE wait_event_type = 'Lock' AND query LIKE 'UPDATE posts SET category%')
                            """, Boolean.class));
                    if (waiting) break;
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                }
                assertTrue(waiting, "글 수정이 관리자 트랜잭션의 행 잠금에 대기해야 한다");
                return request;
            });
            error(outcome.get(10, TimeUnit.SECONDS), 404, "NOT_FOUND");
        }
    }

    @Test
    void repeatedViewsDoNotWaitForPostRowLock() throws Exception {
        long id = fixture(owner, "FREE", "조회 경합", "본문");
        mvc.perform(get(POSTS + "/" + id).header(TOKEN, owner.token())).andExpect(status().isOk());
        try (var executor = Executors.newFixedThreadPool(2)) {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForList("SELECT id FROM posts WHERE id = ? FOR NO KEY UPDATE", Long.class, id);
                var first = executor.submit(() -> mvc.perform(get(POSTS + "/" + id).header(TOKEN, owner.token())));
                var second = executor.submit(() -> mvc.perform(get(POSTS + "/" + id).header(TOKEN, owner.token())));
                try {
                    first.get(3, TimeUnit.SECONDS).andExpect(status().isOk());
                    second.get(3, TimeUnit.SECONDS).andExpect(status().isOk());
                } catch (Exception e) { throw new AssertionError("재조회는 글 행 잠금에 대기하지 않아야 한다", e); }
            });
        }
        assertEquals(1L, jdbc.queryForObject("SELECT view_count FROM posts WHERE id = ?", Long.class, id));
    }
    @Test void concurrentHideRollsBackNewView() throws Exception {
        long id = fixture(owner, "FREE", "숨김 조회 경합", "본문");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = new TransactionTemplate(transactions).execute(tx -> {
                jdbc.update("UPDATE posts SET status = 'HIDDEN', hidden_at = now() WHERE id = ?", id);
                var future = executor.submit(() -> mvc.perform(get(POSTS + "/" + id).header(TOKEN, owner.token())));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !future.isDone()) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'UPDATE posts SET view_count%')", Boolean.class));
                    if (waiting) break;
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                }
                assertTrue(waiting);
                return future;
            });
            request.get(10, TimeUnit.SECONDS).andExpect(status().isNotFound());
        }
        assertEquals(0L, jdbc.queryForObject("SELECT view_count FROM posts WHERE id = ?", Long.class, id));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM post_views WHERE post_id = ?", Long.class, id));
    }
    private void search(String type, String q, long... expected) throws Exception {
        var result = mvc.perform(get(POSTS).param("searchType", type).param("q", q))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(expected.length)));
        for (int i = 0; i < expected.length; i++) {
            result.andExpect(jsonPath("$.content[" + i + "].postId").value(expected[i]));
        }
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post(POSTS).header(TOKEN, owner.token()).contentType(JSON).content(body));
    }

    private ResultActions update(long id, Guest guest, String body) throws Exception {
        return mvc.perform(put(POSTS + "/{id}", id).header(TOKEN, guest.token()).contentType(JSON).content(body));
    }

    private ResultActions remove(long id, Guest guest, String version) throws Exception {
        var request = delete(POSTS + "/{id}", id).header(TOKEN, guest.token());
        return mvc.perform(version == null ? request : request.param("version", version));
    }

    private ResultActions error(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    private Guest guest(String nickname) {
        var user = BoardTestUsers.member(jdbc, nickname);
        return new Guest(user.id(), user.token());
    }

    private long fixture(Guest user, String category, String title, String body) {
        return jdbc.queryForObject("""
                INSERT INTO posts (author_id, category, title, content)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, user.id(), category, title, body);
    }

    private record Guest(long id, String token) {}
}
