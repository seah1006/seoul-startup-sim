package com.seah.startupsim;

import com.seah.startupsim.auth.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 계약 5장: 게시판 쓰기와 내 글 조회는 회원 전용, 읽기는 공개. */
@Testcontainers
@SpringBootTest
class BoardMemberOnlyIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    private static final String TOKEN = "X-Guest-Token";
    private static final String JSON = "application/json";
    private static final String POST_BODY = "{\"category\":\"FREE\",\"title\":\"제목\",\"content\":\"본문\"}";
    private static final String COMMENT_BODY = "{\"content\":\"댓글\"}";
    private static final String WITHDRAWN = "탈퇴한 사장님";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired AuthService auth;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE users CASCADE");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private long article(long authorId) {
        return jdbc.queryForObject("INSERT INTO posts(author_id, category, title, content) VALUES (?, 'FREE', '제목', '본문') RETURNING id",
                Long.class, authorId);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder, String body) {
        return body == null ? builder : builder.contentType(JSON).content(body);
    }

    @Test
    void guestTokenGets403MemberRequiredOnEveryWriteAndMyPosts() throws Exception {
        var member = BoardTestUsers.member(jdbc, "회원사장");
        var guest = BoardTestUsers.guest(jdbc, "손님-" + System.nanoTime());
        long id = article(member.id());
        long commentId = jdbc.queryForObject("INSERT INTO comments(post_id, author_id, content) VALUES (?, ?, '댓글') RETURNING id", Long.class, id, member.id());
        String report = "{\"targetType\":\"POST\",\"targetId\":" + id + ",\"reason\":\"ABUSE\"}";
        String update = "{\"category\":\"FREE\",\"title\":\"수정\",\"content\":\"수정\",\"version\":0}";
        var calls = new MockHttpServletRequestBuilder[][] {
            {withBody(post("/api/board/posts"), POST_BODY)},
            {withBody(put("/api/board/posts/{id}", id), update)},
            {delete("/api/board/posts/{id}", id).param("version", "0")},
            {put("/api/board/posts/{id}/like", id)},
            {delete("/api/board/posts/{id}/like", id)},
            {withBody(post("/api/board/posts/{id}/comments", id), COMMENT_BODY)},
            {withBody(put("/api/board/posts/{id}/comments/{c}", id, commentId), "{\"content\":\"수정\",\"version\":0}")},
            {delete("/api/board/posts/{id}/comments/{c}", id, commentId).param("version", "0")},
            {withBody(post("/api/board/reports"), report)},
            {get("/api/board/me/posts")},
        };
        for (var call : calls) {
            mvc.perform(call[0].header(TOKEN, guest.token())).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MEMBER_REQUIRED"));
        }
    }

    @Test
    void missingTokenGets401OnWritesButReadsStayPublic() throws Exception {
        var member = BoardTestUsers.member(jdbc, "회원사장");
        long id = article(member.id());
        mvc.perform(withBody(post("/api/board/posts"), POST_BODY)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(withBody(post("/api/board/posts/{id}/comments", id), COMMENT_BODY)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/board/posts/{id}/like", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/board/me/posts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/board/posts")).andExpect(status().isOk());
        mvc.perform(get("/api/board/posts/{id}", id)).andExpect(status().isOk());
        mvc.perform(get("/api/board/posts/{id}/comments", id)).andExpect(status().isOk());
        var guest = BoardTestUsers.guest(jdbc, "손님-" + System.nanoTime());
        mvc.perform(get("/api/board/posts/{id}", id).header(TOKEN, guest.token())).andExpect(status().isOk());
    }

    @Test
    void memberCanWrite() throws Exception {
        var member = BoardTestUsers.member(jdbc, "회원사장");
        mvc.perform(withBody(post("/api/board/posts"), POST_BODY).header(TOKEN, member.token()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.authorNickname").value("회원사장"));
        mvc.perform(get("/api/board/me/posts").header(TOKEN, member.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void withdrawnMemberShowsAsWithdrawnOwnerOnPostsAndComments() throws Exception {
        var signup = auth.signup("withdraw_user", "password1", "곧탈퇴", null);
        var user = auth.authenticate(signup.token());
        long id = article(user.userId());
        mvc.perform(withBody(post("/api/board/posts/{id}/comments", id), COMMENT_BODY).header(TOKEN, signup.token()))
                .andExpect(status().isCreated());
        auth.delete(user, "password1");
        mvc.perform(get("/api/board/posts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].authorNickname").value(WITHDRAWN));
        mvc.perform(get("/api/board/posts/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.authorNickname").value(WITHDRAWN));
        mvc.perform(get("/api/board/posts/{id}/comments", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].authorNickname").value(WITHDRAWN));
        mvc.perform(get("/api/board/posts/{id}", id).header(TOKEN, signup.token())).andExpect(status().isUnauthorized());
    }
}
