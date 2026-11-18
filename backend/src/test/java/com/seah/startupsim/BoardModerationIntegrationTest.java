package com.seah.startupsim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
class BoardModerationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    private static final String ADMIN = "/admin/board/";
    private static final String API = "/api/board/reports";
    private record Guest(long id, String token) {}

    private Guest guest() throws Exception {
        String token = UUID.randomUUID().toString();
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        return new Guest(jdbc.queryForObject("INSERT INTO users(nickname, guest_token_hash) VALUES ('가상손님', ?) RETURNING id",
                Long.class, digest), token);
    }

    private long article(Guest guest) {
        return jdbc.queryForObject("""
                INSERT INTO posts(author_id, category, title, content) VALUES (?, 'FREE', '<script>제목</script>', '본문') RETURNING id
                """, Long.class, guest.id());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder report(Guest who, String type, long id) {
        return post(API).header("X-Guest-Token", who.token()).contentType("application/json")
                .content("""
                        {"targetType":"%s","targetId":%d,"reason":"ABUSE","detail":"<script>신고</script>"}
                        """.formatted(type, id));
    }

    @Test void reportOrderAndAtomicResolution() throws Exception {
        var mvc = mvc();
        var owner = guest();
        var other = guest();
        var third = guest();
        long id = article(owner);
        mvc.perform(post(API).contentType("application/json").content("{")).andExpect(status().isUnauthorized());
        mvc.perform(post(API).header("X-Guest-Token", owner.token()).contentType("application/json")
                .content("""
                        {"targetType":"POST","targetId":2147483647,"reason":"ETC","detail":"  "}
                        """)).andExpect(status().isBadRequest());
        mvc.perform(report(owner, "COMMENT", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(report(owner, "POST", id)).andExpect(status().isForbidden());
        mvc.perform(report(other, "POST", id)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(report(other, "POST", id)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_REPORTED"));
        mvc.perform(report(third, "POST", id)).andExpect(status().isCreated());
        long r = jdbc.queryForObject("SELECT min(id) FROM post_reports WHERE target_type = 'POST' AND target_id = ?", Long.class, id);
        mvc.perform(get(ADMIN + "reports")).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get(ADMIN + "reports").with(user("moderator").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get(ADMIN + "reports/" + r).with(user("moderator").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("<script>신고</script>"))));
        mvc.perform(post(ADMIN + "reports/" + r + "/resolve").with(user("moderator").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post(ADMIN + "reports/" + r + "/resolve").with(user("moderator").roles("ADMIN")).with(csrf()))
                .andExpect(status().isSeeOther()).andExpect(flash().attributeExists("message"));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM post_reports WHERE target_type = 'POST' AND target_id = ? AND status = 'RESOLVED' AND handled_by = 'moderator'
                """, Integer.class, id));
        assertEquals("HIDDEN", jdbc.queryForObject("SELECT status FROM posts WHERE id = ?", String.class, id));
        mvc.perform(report(other, "POST", id)).andExpect(status().isNotFound());
        mvc.perform(post(ADMIN + "reports/" + r + "/dismiss").with(user("moderator").roles("ADMIN")).with(csrf()))
                .andExpect(status().isConflict()).andExpect(view().name("admin/board/report"));
        mvc.perform(post(ADMIN + "posts/" + id + "/restore").with(user("moderator").roles("ADMIN")).with(csrf()))
                .andExpect(status().isSeeOther());
        assertNull(jdbc.queryForObject("SELECT hidden_at FROM posts WHERE id = ?", java.time.OffsetDateTime.class, id));
        mvc.perform(report(other, "POST", id)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_REPORTED"));
    }

    @Test void adminListsUseTwentyRowsAndPreserveFilters() throws Exception {
        var mvc = mvc(); var owner = guest(); var admin = user("moderator").roles("ADMIN");
        String title = "paging-" + UUID.randomUUID();
        long id = 0;
        for (int i = 0; i < 21; i++) {
            id = article(owner);
            jdbc.update("UPDATE posts SET title = ? WHERE id = ?", title, id);
        }
        for (int i = 0; i < 21; i++) {
            jdbc.update("INSERT INTO comments(post_id, author_id, content) VALUES (?, ?, '페이징')", id, owner.id());
            jdbc.update("INSERT INTO post_reports(target_type,target_id,reporter_id,reason,status) VALUES ('POST',?,?, 'ABUSE','DISMISSED')", id, guest().id());
        }
        mvc.perform(get(ADMIN + "posts").with(admin).param("q", title)).andExpect(status().isOk())
                .andExpect(model().attribute("posts", org.hamcrest.Matchers.hasSize(20)))
                .andExpect(model().attribute("hasNext", true)).andExpect(content().string(containsString("q=" + title)));
        mvc.perform(get(ADMIN + "posts").with(admin).param("q", title).param("page", "1"))
                .andExpect(model().attribute("posts", org.hamcrest.Matchers.hasSize(1))).andExpect(model().attribute("hasNext", false));
        mvc.perform(get(ADMIN + "posts/" + id).with(admin).param("page", "1"))
                .andExpect(status().isOk()).andExpect(model().attribute("comments", org.hamcrest.Matchers.hasSize(1)));
        mvc.perform(get(ADMIN + "reports").with(admin).param("status", "DISMISSED"))
                .andExpect(status().isOk()).andExpect(model().attribute("reports", org.hamcrest.Matchers.hasSize(20)))
                .andExpect(model().attribute("hasNext", true));
        mvc.perform(get(ADMIN + "posts").with(admin).param("page", "-1")).andExpect(status().isBadRequest());
    }
    @Test void fourthPostIsLimitedOnlyForTheSameGuest() throws Exception {
        var mvc = mvc(); var author = guest(); var other = guest();
        String body = """
                {"category":"FREE","title":"작성 한도","content":"본문"}
                """;
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/board/posts").header("X-Guest-Token", author.token()).contentType("application/json")
                    .content(body)).andExpect(status().isCreated());
        }
        mvc.perform(post("/api/board/posts").header("X-Guest-Token", author.token()).contentType("application/json")
                .content(body)).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mvc.perform(post("/api/board/posts").header("X-Guest-Token", other.token()).contentType("application/json")
                .content(body)).andExpect(status().isCreated());
    }

    @Test void adminFormsPinAndRecomputeCommentCounters() throws Exception {
        var mvc = mvc(); var owner = guest(); var other = guest();
        long id = article(owner);
        long root = jdbc.queryForObject("INSERT INTO comments(post_id, author_id, content, reply_count) VALUES (?, ?, '루트', 1) RETURNING id",
                Long.class, id, owner.id());
        long reply = jdbc.queryForObject("INSERT INTO comments(post_id, author_id, content, parent_id) VALUES (?, ?, '답글', ?) RETURNING id",
                Long.class, id, other.id(), root);
        jdbc.update("UPDATE posts SET comment_count = 2 WHERE id = ?", id);
        var admin = user("moderator").roles("ADMIN");
        String path = ADMIN + "posts/" + id;
        mvc.perform(get(ADMIN + "posts").with(admin)).andExpect(status().isOk());
        mvc.perform(get(path).with(admin)).andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;제목&lt;/script&gt;")));
        mvc.perform(post(path + "/pin").with(admin).with(csrf()).param("pinned", "oops"))
                .andExpect(status().isBadRequest()).andExpect(view().name("admin/board/post"));
        mvc.perform(post(path + "/pin").with(admin).with(csrf()).param("pinned", "true")).andExpect(status().isSeeOther());
        assertTrue(jdbc.queryForObject("SELECT is_pinned FROM posts WHERE id = ?", Boolean.class, id));
        for (String action : new String[]{"hide", "hide", "restore", "delete"}) {
            mvc.perform(post(ADMIN + "comments/" + reply + "/" + action).with(admin).with(csrf())).andExpect(status().isSeeOther());
            int visibleReplies = action.equals("restore") ? 1 : 0;
            assertEquals(visibleReplies, jdbc.queryForObject("SELECT reply_count FROM comments WHERE id = ?", Integer.class, root));
            assertEquals(visibleReplies + 1, jdbc.queryForObject("SELECT comment_count FROM posts WHERE id = ?", Integer.class, id));
        }
        mvc.perform(post(ADMIN + "comments/" + reply + "/restore").with(admin).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post(path + "/hide").with(admin).with(csrf())).andExpect(status().isSeeOther());
        mvc.perform(post(path + "/pin").with(admin).with(csrf()).param("pinned", "false")).andExpect(status().isConflict());
    }
}
