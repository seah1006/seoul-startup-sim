package com.seah.startupsim;

import org.junit.jupiter.api.DisplayName;
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
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 이벤트 정의 관리자 화면의 보안·CSRF·토글·검증 저장 경로를 실제 템플릿으로 검증한다. */
@Testcontainers
@SpringBootTest
class AdminEventWebIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired tools.jackson.databind.ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("세 목록은 관리자만 보고 시드 정의 수만큼 행을 보여 준다")
    void lists() throws Exception {
        mvc().perform(get("/admin/turning-points")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
        mvc().perform(get("/admin/turning-points").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(model().attribute("rows", hasSize(12)))
                .andExpect(content().string(containsString("최근 30일 발생")));
        mvc().perform(get("/admin/reactions").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(model().attribute("rows", hasSize(36)));
        mvc().perform(get("/admin/moments").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(model().attribute("rows", hasSize(12)));
        mvc().perform(get("/admin/moments/999999").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("토글은 CSRF가 필요하고 활성을 뒤집으며 리비전을 올린다")
    void toggle() throws Exception {
        long id = jdbc.queryForObject("SELECT id FROM moment_templates ORDER BY code LIMIT 1", Long.class);
        int rev = jdbc.queryForObject("SELECT rev FROM moment_templates WHERE id = ?", Integer.class, id);
        String path = "/admin/moments/" + id + "/toggle";
        mvc().perform(post(path).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc().perform(post(path).with(user("user").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        mvc().perform(post(path).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/moments"));
        assertEquals(false, jdbc.queryForObject("SELECT enabled FROM moment_templates WHERE id = ?", Boolean.class, id));
        assertEquals(rev + 1, jdbc.queryForObject("SELECT rev FROM moment_templates WHERE id = ?", Integer.class, id));
        mvc().perform(post(path).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().is3xxRedirection());
        assertEquals(true, jdbc.queryForObject("SELECT enabled FROM moment_templates WHERE id = ?", Boolean.class, id));
    }

    @Test
    @DisplayName("JSON 편집은 같은 검증기로 거부하면 오류 경로를 보여 주고 저장하지 않는다")
    void edit() throws Exception {
        long id = jdbc.queryForObject("SELECT id FROM turning_points WHERE code = 'RENT_HIKE'", Long.class);
        String path = "/admin/turning-points/" + id;
        // 마이그레이션이 정의를 조정하면 리비전이 1보다 클 수 있으므로 시작 값 기준으로 본다.
        int rev = jdbc.queryForObject("SELECT rev FROM turning_points WHERE id = ?", Integer.class, id);
        String original = (String) mvc().perform(get(path).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("definition");
        var unknown = (tools.jackson.databind.node.ObjectNode) json.readTree(original);
        unknown.set("conditionExpr", json.readTree("{\"var\":\"secret.sql\",\"op\":\"==\",\"value\":1}"));
        String unknownVariable = json.writeValueAsString(unknown);
        mvc().perform(post(path).param("definition", unknownVariable).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("conditionExpr")))
                .andExpect(content().string(containsString("저장하지 않았습니다")));
        mvc().perform(post(path).param("definition", "{not json").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("JSON 형식")));
        var range = (tools.jackson.databind.node.ObjectNode) json.readTree(original);
        range.put("baseProbability", 1.5);
        String outOfRange = json.writeValueAsString(range);
        mvc().perform(post(path).param("definition", outOfRange).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("baseProbability")));
        var code = (tools.jackson.databind.node.ObjectNode) json.readTree(original);
        code.put("code", "RENT_HIKE_TWO");
        String renamed = json.writeValueAsString(code);
        mvc().perform(post(path).param("definition", renamed).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc().perform(post(path).param("definition", original).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        assertEquals(rev, jdbc.queryForObject("SELECT rev FROM turning_points WHERE id = ?", Integer.class, id));
        var title = (tools.jackson.databind.node.ObjectNode) json.readTree(original);
        title.put("title", "임대료 재계약 통보");
        String retitled = json.writeValueAsString(title);
        mvc().perform(post(path).param("definition", retitled).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(path));
        assertEquals("임대료 재계약 통보", jdbc.queryForObject("SELECT title FROM turning_points WHERE id = ?", String.class, id));
        assertEquals(rev + 1, jdbc.queryForObject("SELECT rev FROM turning_points WHERE id = ?", Integer.class, id));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM turning_point_options WHERE turning_point_id = ?", Integer.class, id));
    }
}
