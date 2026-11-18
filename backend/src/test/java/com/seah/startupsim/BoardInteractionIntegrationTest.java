package com.seah.startupsim;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BoardInteractionIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    private static final String POSTS = "/api/board/posts/";
    private static final String CONTENT = "{\"content\":\" 댓글 \"}";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DB::getJdbcUrl);
        properties.add("spring.datasource.username", DB::getUsername);
        properties.add("spring.datasource.password", DB::getPassword);
    }

    @AfterEach
    void clear() {
        jdbc.execute("TRUNCATE users CASCADE");
    }

    private Fixture fixture(int users) {
        return new TransactionTemplate(transactions).execute(status -> {
            List<String> tokens = new ArrayList<>();
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < users; i++) {
                String token = UUID.randomUUID().toString();
                tokens.add(token);
                ids.add(jdbc.queryForObject("INSERT INTO users (nickname, guest_token_hash) VALUES (?, ?) RETURNING id",
                        Long.class, "테스트손님" + i, digest(token)));
            }
            return new Fixture(post(ids.getFirst()), post(ids.getFirst()), tokens);
        });
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private long post(long author) {
        return jdbc.queryForObject("""
                INSERT INTO posts (author_id, category, title, content) VALUES (?, 'FREE', '제목', '본문') RETURNING id
                """, Long.class, author);
    }

    private void parallel(int workers, IntFunction<HttpRequest> requests, int status) throws Exception {
        try (var pool = Executors.newFixedThreadPool(workers)) {
            var ready = new CountDownLatch(workers);
            var go = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                var request = requests.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(10, TimeUnit.SECONDS));
                    return http.send(request, HttpResponse.BodyHandlers.ofString());
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                go.countDown();
            }
            for (var future : futures) expect(future.get(20, TimeUnit.SECONDS), status);
        }
    }

    private HttpRequest request(String method, String path, String token, String body) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("X-Guest-Token", token);
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        return http.send(request(method, path, token, body), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response::body);
        return response.body().isEmpty() ? null : json.readTree(response.body());
    }

    private void error(HttpResponse<String> response, int status, String code) {
        var body = expect(response, status);
        assertEquals(code, body.path("code").asText());
        assertFalse(body.path("message").asText().isBlank());
        assertFalse(body.path("timestamp").asText().isBlank());
    }

    private long created(String path, String token, String body) throws Exception {
        var comment = expect(send("POST", path, token, body), 201);
        assertTrue(comment.path("isMine").asBoolean());
        assertEquals(0, comment.path("version").asInt(-1));
        assertTrue(comment.path("commentId").asLong() > 0);
        return comment.path("commentId").asLong();
    }

    private void assertLikes(long post, int expected) {
        assertEquals(expected, count("SELECT count(*) FROM post_likes WHERE post_id = ?", post));
        assertEquals(expected, count("SELECT like_count FROM posts WHERE id = ?", post));
    }

    private long count(String query, long id) { return jdbc.queryForObject(query, Long.class, id); }
    private Map<String, Object> postStamp(long id) { return jdbc.queryForMap("SELECT version, updated_at FROM posts WHERE id = ?", id); }
    private Map<String, Object> commentStamp(long id) { return jdbc.queryForMap("SELECT version, updated_at FROM comments WHERE id = ?", id); }
    private static String comments(long id) { return POSTS + id + "/comments"; }
    private static String like(long id) { return POSTS + id + "/like"; }
    private static String reply(long id) { return "{\"content\":\"답글\",\"parentId\":" + id + "}"; }
    private record Fixture(long a, long b, List<String> tokens) {}

    @Test
    void concurrentRepliesToOneRootBothCommit() throws Exception {
        Fixture f = fixture(2);
        String path = comments(f.a());
        long root = created(path, f.tokens().getFirst(), CONTENT);
        var rootBefore = commentStamp(root);
        var postBefore = postStamp(f.a());
        parallel(2, i -> request("POST", path, f.tokens().get(i), reply(root)), 201);
        assertEquals(2, count("SELECT count(*) FROM comments WHERE parent_id = ? AND status = 'ACTIVE'", root));
        assertEquals(2, count("SELECT reply_count FROM comments WHERE id = ?", root));
        assertEquals(3, count("SELECT comment_count FROM posts WHERE id = ?", f.a()));
        assertEquals(rootBefore, commentStamp(root));
        assertEquals(postBefore, postStamp(f.a()));
    }

    @Test
    void concurrentLikeRequestsCountInsertedRowsAndUnlikeIsIdempotent() throws Exception {
        Fixture f = fixture(10);
        var aBefore = postStamp(f.a());
        var bBefore = postStamp(f.b());
        parallel(10, i -> request("PUT", like(f.a()), f.tokens().getFirst(), null), 204);
        assertLikes(f.a(), 1);
        parallel(10, i -> request("PUT", like(f.b()), f.tokens().get(i), null), 204);
        assertLikes(f.b(), 10);
        parallel(10, i -> request("DELETE", like(f.a()), f.tokens().getFirst(), null), 204);
        assertLikes(f.a(), 0);
        assertEquals(aBefore, postStamp(f.a()));
        assertEquals(bBefore, postStamp(f.b()));
    }

    @Test
    void hiddenAndDeletedPostsRejectLikesWithoutChangingRowsOrCounters() throws Exception {
        Fixture f = fixture(2);
        String owner = f.tokens().getFirst();
        String other = f.tokens().getLast();
        expect(send("PUT", like(f.a()), owner, null), 204);
        jdbc.update("UPDATE posts SET status = 'HIDDEN', hidden_at = now() WHERE id = ?", f.a());
        jdbc.update("UPDATE posts SET status = 'DELETED', deleted_at = now() WHERE id = ?", f.b());
        for (long post : new long[]{f.a(), f.b()}) {
            var before = postStamp(post);
            error(send("PUT", like(post), other, null), 404, "NOT_FOUND");
            error(send("DELETE", like(post), owner, null), 404, "NOT_FOUND");
            error(send("POST", comments(post), owner, CONTENT), 404, "NOT_FOUND");
            error(send("GET", comments(post), null, null), 404, "NOT_FOUND");
            error(send("GET", POSTS + post, owner, null), 404, "NOT_FOUND");
            error(send("PUT", POSTS + post, other, "{\"category\":\"FREE\",\"title\":\"t\",\"content\":\"c\",\"version\":0}"), 404, "NOT_FOUND");
            error(send("DELETE", POSTS + post + "?version=0", other, null), 404, "NOT_FOUND");
            error(send("PUT", POSTS + post, other, "{}"), 400, "VALIDATION_FAILED");
            assertEquals(0, count("SELECT count(*) FROM post_views WHERE post_id = ?", post));
            assertEquals(0, count("SELECT comment_count FROM posts WHERE id = ?", post));
            assertEquals(before, postStamp(post));
        }
        assertLikes(f.a(), 1);
        assertLikes(f.b(), 0);
    }

    @Test
    void validatesCommentParentAuthorizationAndVersionInOrder() throws Exception {
        Fixture f = fixture(2);
        String owner = f.tokens().getFirst();
        String other = f.tokens().getLast();
        String path = comments(f.a());
        long root = created(path, owner, CONTENT);
        long child = created(path, other, reply(root));
        long foreign = created(comments(f.b()), owner, CONTENT);
        error(send("POST", path, owner, reply(child)), 400, "COMMENT_DEPTH_EXCEEDED");
        error(send("POST", path, owner, reply(foreign)), 404, "NOT_FOUND");
        error(send("GET", path + "/" + child + "/replies", null, null), 404, "NOT_FOUND");
        error(send("POST", path, null, "{"), 401, "UNAUTHORIZED");
        error(send("GET", path + "?page=-1", UUID.randomUUID().toString(), null), 401, "UNAUTHORIZED");
        error(send("GET", path + "?size=51", null, null), 400, "VALIDATION_FAILED");
        error(send("POST", path, owner, "{\"content\":\"c\",\"parentId\":\"1\"}"), 400, "VALIDATION_FAILED");
        String edit = "{\"content\":\" 새 본문 \",\"version\":0}";
        String target = path + "/" + root;
        error(send("PUT", target, other, "{}"), 400, "VALIDATION_FAILED");
        error(send("PUT", target, other, edit), 403, "FORBIDDEN");
        var edited = expect(send("PUT", target, owner, edit), 200);
        assertEquals("새 본문", edited.path("content").asText());
        assertEquals(1, edited.path("version").asLong());
        error(send("PUT", target, owner, edit), 409, "COMMENT_VERSION_CONFLICT");
        error(send("DELETE", target + "?version=0", owner, null), 409, "COMMENT_VERSION_CONFLICT");
        assertEquals(2, count("SELECT comment_count FROM posts WHERE id = ?", f.a()));
    }

    @Test
    void pagesThreadsSeparatelyAndKeepsPlaceholderOnlyWhileRepliesExist() throws Exception {
        Fixture f = fixture(2);
        String owner = f.tokens().getFirst();
        String other = f.tokens().getLast();
        String path = comments(f.a());
        var postBefore = postStamp(f.a());
        long root = created(path, owner, CONTENT);
        long root2 = created(path, other, CONTENT);
        long child = created(path, other, reply(root));
        long child2 = created(path, owner, reply(root));
        var first = send("GET", path + "?page=0&size=1", owner, null);
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
        var roots = expect(first, 200);
        assertEquals(2, roots.path("totalElements").asLong());
        assertEquals(2, roots.path("totalPages").asLong());
        assertEquals(root, roots.path("content").get(0).path("commentId").asLong());
        assertTrue(roots.path("content").get(0).path("isMine").asBoolean());
        var secondPage = expect(send("GET", path + "?page=1&size=1", null, null), 200);
        assertEquals(root2, secondPage.path("content").get(0).path("commentId").asLong());
        assertFalse(secondPage.path("content").get(0).path("isMine").asBoolean());
        String target = path + "/" + root;
        String replies = target + "/replies";
        var secondReply = expect(send("GET", replies + "?page=1&size=1", null, null), 200);
        assertEquals(2, secondReply.path("totalElements").asLong());
        assertEquals(child2, secondReply.path("content").get(0).path("commentId").asLong());
        assertTrue(expect(send("GET", replies + "?page=99&size=1", null, null), 200).path("content").isEmpty());

        expect(send("DELETE", target + "?version=0", owner, null), 204);
        error(send("DELETE", target + "?version=0", owner, null), 404, "NOT_FOUND");
        error(send("POST", path, owner, reply(root)), 404, "NOT_FOUND");
        var placeholder = expect(send("GET", path, owner, null), 200).path("content").get(0);
        assertEquals(root, placeholder.path("commentId").asLong());
        assertTrue(placeholder.path("isPlaceholder").asBoolean());
        assertFalse(placeholder.path("isMine").asBoolean());
        assertEquals("표시할 수 없는 댓글입니다.", placeholder.path("content").asText());
        for (String field : List.of("authorNickname", "updatedAt", "version")) assertTrue(placeholder.path(field).isNull());
        assertEquals(2, placeholder.path("replyCount").asInt());
        assertEquals(3, count("SELECT comment_count FROM posts WHERE id = ?", f.a()));
        var rootDeleted = commentStamp(root);
        expect(send("DELETE", path + "/" + child + "?version=0", other, null), 204);
        assertEquals(1, count("SELECT reply_count FROM comments WHERE id = ?", root));
        expect(send("DELETE", path + "/" + child2 + "?version=0", owner, null), 204);
        assertEquals(1, count("SELECT comment_count FROM posts WHERE id = ?", f.a()));
        assertEquals(0, count("SELECT reply_count FROM comments WHERE id = ?", root));
        assertEquals(rootDeleted, commentStamp(root));
        assertEquals(postBefore, postStamp(f.a()));
        assertEquals(1, expect(send("GET", path, null, null), 200).path("totalElements").asLong());
        error(send("GET", replies, null, null), 404, "NOT_FOUND");
    }
}
