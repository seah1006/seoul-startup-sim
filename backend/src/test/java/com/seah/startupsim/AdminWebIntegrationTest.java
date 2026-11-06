package com.seah.startupsim;

import java.net.URI;
import java.net.CookieManager;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ADMIN_USERNAME=fixture-admin", "ADMIN_PASSWORD=fixture-password"})
class AdminWebIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl); r.add("spring.datasource.username", DB::getUsername); r.add("spring.datasource.password", DB::getPassword);
    }
    @Autowired WebApplicationContext context;
    @Autowired Environment environment;
    MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    @Test @DisplayName("관리자 화면은 로그인 리다이렉트하고 관리자 API는 401을 반환한다")
    void security() throws Exception {
        mvc().perform(get("/admin")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        mvc().perform(post("/admin/api/ingest/test").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc().perform(post("/admin/ingest/run").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
    }
    @Test @DisplayName("관리자 대시보드와 목록 및 상세를 실제 템플릿으로 렌더링한다")
    void pages() throws Exception {
        mvc().perform(get("/admin").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(model().attributeExists("cards", "circuit")).andExpect(content().string(org.hamcrest.Matchers.containsString("수집 대시보드")));
        mvc().perform(get("/admin/ingest/runs").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(model().attributeExists("runs"));
        var jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        Long id = jdbc.queryForObject("INSERT INTO ingest_runs(source,status) VALUES ('test','SUCCEEDED') RETURNING id", Long.class);
        mvc().perform(get("/admin/ingest/runs/" + id).with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(model().attributeExists("run", "raws"));
        mvc().perform(get("/admin/ingest/run").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        mvc().perform(get("/admin/data-versions").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    }
    @Test @DisplayName("공개 API의 400 404 405 오류는 공통 계약으로 반환한다")
    void errors() throws Exception {
        mvc().perform(get("/api/areas")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.timestamp").exists());
        mvc().perform(get("/api/areas?bbox=bad&zoom=oops")).andExpect(status().isBadRequest());
        mvc().perform(get("/api/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc().perform(post("/api/areas")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
    static HttpResponse<String> authenticatedPost(String port, String path) throws Exception {
        String base = "http://localhost:" + port;
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String login = client.send(HttpRequest.newBuilder(URI.create(base + "/admin/login")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        var matcher = java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login);
        if (!matcher.find()) throw new IllegalStateException("CSRF 토큰 없음");
        String token = java.net.URLEncoder.encode(matcher.group(1), java.nio.charset.StandardCharsets.UTF_8);
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/admin/login")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username=fixture-admin&password=fixture-password&_csrf=" + token)).build(), HttpResponse.BodyHandlers.ofString());
        org.junit.jupiter.api.Assertions.assertTrue(response.statusCode() >= 300 && response.statusCode() < 400);
        String page = client.send(HttpRequest.newBuilder(URI.create(base + "/admin/ingest/run")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        matcher = java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page);
        if (!matcher.find()) throw new IllegalStateException("CSRF 토큰 없음");
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("_csrf=" + java.net.URLEncoder.encode(matcher.group(1), java.nio.charset.StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
    }
}


