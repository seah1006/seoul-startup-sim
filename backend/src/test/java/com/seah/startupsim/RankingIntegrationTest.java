package com.seah.startupsim;

import com.seah.startupsim.game.GameRecordRepository;
import com.seah.startupsim.game.GuestAuth;
import com.seah.startupsim.ranking.RankingBatch;
import com.seah.startupsim.ranking.RankingKind;
import com.seah.startupsim.ranking.RankingRepository;
import com.seah.startupsim.ranking.RankingService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스냅샷 배치의 공동 순위·정렬 키·KST 경계·잠금과 공개 조회 API 범위를 검증한다. */
@Testcontainers
@SpringBootTest
class RankingIntegrationTest {
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
    @Autowired RankingBatch batch;
    @Autowired RankingRepository repository;
    @Autowired RankingService rankings;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    long user;

    @BeforeEach
    void fixture() {
        jdbc.execute("TRUNCATE users CASCADE");
        user = auth.create().userId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    /** 랭킹은 game_records만 읽으므로 엔딩 원본 행을 직접 만든다. */
    long record(long owner, Instant endedAt, int quarters, long profit, long best, int shops, String service, String area) {
        long game = jdbc.queryForObject("""
                INSERT INTO games (user_id, seed, start_quarter, last_quarter, cash, initial_capital, settings, status, ending_type)
                VALUES (?, 1, '20231', '20262', 0, 0, '{}'::jsonb, 'ENDED', 'LONG_RUNNING') RETURNING id
                """, Long.class, owner);
        jdbc.update("""
                INSERT INTO game_records (game_id, user_id, nickname, ending_type, survived_quarters, total_profit,
                    best_quarter_profit, shop_count, area_code, area_name, service_code, service_name, shop_name,
                    owner_name, ended_at, ranking_date)
                SELECT ?, id, nickname, 'LONG_RUNNING', ?, ?, ?, ?, ?, ?, ?, ?, ?, '사장', ?, ? FROM users WHERE id = ?
                """, game, quarters, profit, best, shops, area, area + " 상권", service, service + " 업종", "가게" + game,
                Timestamp.from(endedAt), Date.valueOf(GameRecordRepository.rankingDate(endedAt)), owner);
        return game;
    }

    Instant todayNoon() {
        return rankings.today().atTime(12, 0).atZone(GameRecordRepository.KST).toInstant();
    }

    @Test
    @DisplayName("공동 순위(RANK)와 정렬 키 4단계: 생존 분기 → 누적 순이익 → 최고 분기 → 매장 수")
    void tiesAndSortKeys() throws Exception {
        Instant at = todayNoon();
        long a = record(user, at, 10, 100, 50, 2, "CS1", "A");
        long b = record(user, at, 10, 100, 50, 1, "CS1", "A");
        long c = record(user, at, 10, 100, 50, 1, "CS2", "B");
        long d = record(user, at, 10, 100, 40, 5, "CS1", "A");
        long e = record(user, at, 10, 90, 99, 5, "CS2", "B");
        long f = record(user, at, 9, 999, 999, 5, "CS2", "B");
        assertTrue(batch.refresh(at).executed());
        var all = repository.entries(rankings.today(), RankingKind.ALL, "", 100);
        assertEquals(List.of(1, 2, 2, 4, 5, 6), all.stream().map(RankingRepository.Entry::rank).toList());
        assertEquals(List.of(a, Math.min(b, c), Math.max(b, c), d, e, f),
                all.stream().map(RankingRepository.Entry::gameId).toList());
        var industry = repository.entries(rankings.today(), RankingKind.INDUSTRY, "CS2", 100);
        assertEquals(List.of(c, e, f), industry.stream().map(RankingRepository.Entry::gameId).toList());
        assertEquals(List.of(1, 2, 3), industry.stream().map(RankingRepository.Entry::rank).toList());
        assertEquals(List.of(a, b, d), repository.entries(rankings.today(), RankingKind.AREA, "A", 100).stream()
                .map(RankingRepository.Entry::gameId).toList());
        mvc.perform(get("/api/rankings").param("kind", "INDUSTRY").param("key", "CS1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(rankings.today().toString()))
                .andExpect(jsonPath("$.kind").value("INDUSTRY"))
                .andExpect(jsonPath("$.refreshedAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].gameId").value(a))
                .andExpect(jsonPath("$.entries[0].shopName").value("가게" + a))
                .andExpect(jsonPath("$.entries[0].serviceName").value("CS1 업종"))
                .andExpect(jsonPath("$.entries[0].areaName").value("A 상권"))
                .andExpect(jsonPath("$.entries[0].ownerName").value("사장"))
                .andExpect(jsonPath("$.entries[0].endingType").value("LONG_RUNNING"))
                .andExpect(jsonPath("$.entries[0].survivedQuarters").value(10))
                .andExpect(jsonPath("$.entries[0].totalProfit").value(100))
                .andExpect(jsonPath("$.entries[0].nickname").exists());
        mvc.perform(get("/api/rankings").param("limit", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ALL")).andExpect(jsonPath("$.entries.length()").value(2));
    }

    @Test
    @DisplayName("KST 자정 경계: 23:50 엔딩은 그날, 00:10은 다음 날, 00시대 배치는 어제도 갱신한다")
    void midnightBoundary() {
        Instant beforeMidnight = Instant.parse("2026-11-18T14:50:00Z");
        Instant afterMidnight = Instant.parse("2026-11-18T15:10:00Z");
        LocalDate day = LocalDate.parse("2026-11-18");
        LocalDate next = LocalDate.parse("2026-11-19");
        long late = record(user, beforeMidnight, 5, 10, 5, 1, "CS1", "A");
        long early = record(user, afterMidnight, 5, 10, 5, 1, "CS1", "A");
        // 01:00 배치는 오늘만, 00:20 배치는 어제도 갱신한다.
        assertEquals(List.of(next), RankingBatch.targetDates(Instant.parse("2026-11-18T16:00:00Z")));
        assertEquals(List.of(next, day), RankingBatch.targetDates(Instant.parse("2026-11-18T15:20:00Z")));
        assertEquals(List.of(day), RankingBatch.targetDates(Instant.parse("2026-11-18T14:59:00Z")));
        batch.refresh(Instant.parse("2026-11-18T16:00:00Z"));
        assertEquals(List.of(early), ids(next));
        assertEquals(List.of(), ids(day));
        batch.refresh(Instant.parse("2026-11-18T15:20:00Z"));
        assertEquals(List.of(late), ids(day));
        assertEquals(List.of(early), ids(next));
    }

    List<Long> ids(LocalDate date) {
        return repository.entries(date, RankingKind.ALL, "", 100).stream().map(RankingRepository.Entry::gameId).toList();
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠금을 쥐고 있으면 배치는 건너뛰고 스냅샷을 바꾸지 않는다")
    void concurrentBatchRunsOnce() throws Exception {
        Instant at = todayNoon();
        long first = record(user, at, 5, 10, 5, 1, "CS1", "A");
        batch.refresh(at);
        record(user, at, 6, 10, 5, 1, "CS1", "A");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, RankingBatch.LOCK_KEY);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(locked.await(30, TimeUnit.SECONDS));
            assertFalse(batch.refresh(at).executed());
            assertEquals(List.of(first), ids(rankings.today()));
            release.countDown();
            holder.get();
        }
        assertTrue(batch.refresh(at).executed());
        assertEquals(2, ids(rankings.today()).size());
    }

    @Test
    @DisplayName("파티션별 상위 100위까지만 저장한다")
    void topHundred() {
        Instant at = todayNoon();
        for (int index = 0; index < 102; index++) {
            record(user, at, 5, index, 0, 1, "CS1", "A");
        }
        batch.refresh(at);
        assertEquals(100, jdbc.queryForObject("""
                SELECT count(*) FROM ranking_snapshots WHERE kind = 'ALL' AND ranking_date = ?
                """, Integer.class, Date.valueOf(rankings.today())));
        assertEquals(100, jdbc.queryForObject("SELECT max(rank) FROM ranking_snapshots WHERE kind = 'INDUSTRY'", Integer.class));
    }

    @Test
    @DisplayName("조회 범위: 7일 밖·미래 400, INDUSTRY/AREA key 누락 400, limit 범위 밖 400, 인증 불필요")
    void requestValidation() throws Exception {
        LocalDate today = rankings.today();
        mvc.perform(get("/api/rankings")).andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(0));
        mvc.perform(get("/api/rankings").param("date", today.minusDays(6).toString())).andExpect(status().isOk());
        for (var params : List.of(List.of("date", today.minusDays(7).toString()), List.of("date", today.plusDays(1).toString()),
                List.of("date", "2026-13-01"), List.of("kind", "INDUSTRY"), List.of("kind", "AREA"),
                List.of("kind", "WEEKLY"), List.of("limit", "101"), List.of("limit", "0"))) {
            mvc.perform(get("/api/rankings").param(params.get(0), params.get(1)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(get("/api/rankings").param("kind", "AREA").param("key", " ")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/rankings").param("kind", "AREA").param("key", "A")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("닉네임은 엔딩 시점 스냅샷을 쓰고, 탈퇴한 사용자는 표시 시 '탈퇴한 사장님'으로 바뀐다")
    void nicknameSnapshotAndWithdrawal() throws Exception {
        Instant at = todayNoon();
        long other = auth.create().userId();
        String snapshot = jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, user);
        record(user, at, 9, 10, 5, 1, "CS1", "A");
        record(other, at, 8, 10, 5, 1, "CS1", "A");
        batch.refresh(at);
        jdbc.update("UPDATE users SET nickname = '바뀐닉네임' WHERE id = ?", user);
        jdbc.update("UPDATE users SET deleted_at = now(), nickname = '탈퇴한 사장님' WHERE id = ?", other);
        mvc.perform(get("/api/rankings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].nickname").value(snapshot))
                .andExpect(jsonPath("$.entries[1].nickname").value(GameRecordRepository.DELETED_NICKNAME));
    }

    @Test
    @DisplayName("관리자 랭킹 화면: 로그인 필요, 마지막 갱신 표시, 지금 갱신은 CSRF와 같은 배치 사용")
    void adminPage() throws Exception {
        mvc.perform(get("/admin/rankings")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/rankings").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("지금 갱신")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("아직 없음")));
        mvc.perform(post("/admin/rankings/refresh").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        record(user, Instant.now(), 5, 10, 5, 1, "CS1", "A");
        mvc.perform(post("/admin/rankings/refresh").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/rankings"));
        assertEquals(1, ids(rankings.today()).size());
        mvc.perform(get("/admin/rankings").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("아직 없음"))));
    }
}
