package com.seah.startupsim;

import com.seah.startupsim.auth.AuthException;
import com.seah.startupsim.auth.AuthService;
import com.seah.startupsim.common.IpRateLimitFilter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 보안 체인과 주입 시계로 계정 HTTP 계약과 경계 조건을 검증한다. */
@Testcontainers
@SpringBootTest
class AuthWebIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    AuthService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    IpRateLimitFilter limiter;
    @MockitoBean
    Clock clock;
    MockMvc mvc;
    final Instant start = Instant.parse("2026-11-18T10:00:00Z");

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(start);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(limiter).build();
    }

    String id() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    String secret() {
        return UUID.randomUUID().toString() + "a1";
    }

    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Map<String, String> fields) {
        return request.contentType("application/json").content(json.writeValueAsString(fields));
    }

    @Test
    void missingAndInvalidAuthenticationPrecedeBodyValidation() throws Exception {
        for (var request : new MockHttpServletRequestBuilder[] {
                get("/api/auth/me"), patch("/api/auth/me"), put("/api/auth/me/password"),
                delete("/api/auth/me"), post("/api/auth/logout")}) {
            mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullAccountHttpLifecycleAndHeaderPriority() throws Exception {
        var guest = service.createGuest();
        String login = id();
        String password = secret();
        var response = mvc.perform(body(post("/api/auth/signup"), Map.of("loginId", login,
                        "password", password, "nickname", id())).header("X-Guest-Token", guest.guestToken()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.userId").value(guest.userId()))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(response).path("token").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)
                        .header("X-Guest-Token", "invalid")).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER")).andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(get("/api/auth/me").header("X-Guest-Token", guest.guestToken())).andExpect(status().isUnauthorized());
        mvc.perform(body(patch("/api/auth/me"), Map.of("nickname", id())).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String replacement = secret();
        mvc.perform(body(put("/api/auth/me/password"), Map.of("currentPassword", password, "newPassword", replacement))
                .header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(body(delete("/api/auth/me"), Map.of("password", replacement))
                .header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void guestCannotModifyMemberProfileAndLongUtf8PasswordIsRejected() throws Exception {
        var guest = service.createGuest();
        mvc.perform(body(patch("/api/auth/me"), Map.of("nickname", id())).header("X-Guest-Token", guest.guestToken()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBER_REQUIRED"));
        mvc.perform(body(post("/api/auth/signup"), Map.of("loginId", id(), "password", "가".repeat(25) + "a1", "nickname", id())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void loginLogoutAndUniformFailureMessages() throws Exception {
        String login = id();
        String password = secret();
        service.signup(login, password, id(), null);
        String wrong = secret();
        String known = mvc.perform(body(post("/api/auth/login"), Map.of("loginId", login, "password", wrong)))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(body(post("/api/auth/login"), Map.of("loginId", id(), "password", wrong)))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(known).path("message").asText(), json.readTree(unknown).path("message").asText());
        assertFalse(known.contains(wrong));
        mvc.perform(body(post("/api/auth/login"), Map.of("loginId", login, "password", "가".repeat(25) + "a1")))
                .andExpect(status().isBadRequest());
        String result = mvc.perform(body(post("/api/auth/login"), Map.of("loginId", login, "password", password)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = json.readTree(result).path("token").asText();
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenExpiresAndMemberUseExtendsExpiry() {
        var guest = service.createGuest();
        var member = service.signup(id(), secret(), id(), null);
        when(clock.instant()).thenReturn(start.plus(Duration.ofDays(13)));
        service.authenticate(member.token());
        when(clock.instant()).thenReturn(start.plus(Duration.ofDays(15)));
        service.authenticate(member.token());
        when(clock.instant()).thenReturn(start.plus(Duration.ofDays(31)));
        assertEquals(401, assertThrows(AuthException.class, () -> service.authenticate(guest.guestToken())).status);
        assertEquals(401, assertThrows(AuthException.class, () -> service.authenticate(member.token())).status);
    }

    @Test
    void concurrentFailuresAreCountedExactlyAndLockExpires() throws Exception {
        String login = id();
        String password = secret();
        var member = service.signup(login, password, id(), null);
        try (var executor = Executors.newFixedThreadPool(10)) {
            var calls = new ArrayList<Callable<Integer>>();
            for (int index = 0; index < 10; index++) {
                calls.add(() -> assertThrows(AuthException.class, () -> service.login(login, secret())).status);
            }
            for (var result : executor.invokeAll(calls)) {
                assertTrue(result.get() == 401 || result.get() == 423);
            }
        }
        assertEquals(10, jdbc.queryForObject("SELECT failed_login_count FROM users WHERE id = ?", Integer.class, member.user().userId()));
        assertEquals(423, assertThrows(AuthException.class, () -> service.login(login, password)).status);
        when(clock.instant()).thenReturn(start.plus(Duration.ofMinutes(16)));
        assertEquals(member.user().userId(), service.login(login, password).user().userId());
    }

    @Test
    void authenticationIpLimitReturns429() throws Exception {
        String ip = "198.51.100.73";
        for (int index = 0; index < 20; index++) {
            mvc.perform(body(post("/api/auth/login"), Map.of("loginId", id(), "password", secret()))
                    .with(request -> { request.setRemoteAddr(ip); return request; }))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(body(post("/api/auth/login"), Map.of("loginId", id(), "password", secret()))
                .with(request -> { request.setRemoteAddr(ip); return request; }))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void flywayAppliesIngestBeforeAuth() {
        var versions = jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank", String.class);
        assertTrue(versions.indexOf("7.1") >= 0);
        assertTrue(versions.indexOf("7.1") < versions.indexOf("7.2"));
    }
}
